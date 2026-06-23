/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.ICommandArgument;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ItemById;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidTypeException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class GetCommand extends Command {

    public GetCommand(IBaritone baritone) {
        super(baritone, "get");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        String raw = args.rawRest().trim();
        if (raw.startsWith("[")) {
            Map<Item, Integer> requests = parseBatch(raw, args.peek());
            baritone.getGetProcess().get(requests);
            logDirect("Getting " + describeBatch(requests));
            return;
        }
        int quantity = args.getAsOrDefault(Integer.class, 1);
        args.requireExactly(1);
        Item item = args.getDatatypeFor(ItemById.INSTANCE);
        baritone.getGetProcess().get(item, quantity);
        logDirect("Getting " + quantity + " " + BuiltInRegistries.ITEM.getKey(item));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasAny() && args.rawRest().trim().startsWith("[")) {
            return tabCompleteBatch(args.rawRest());
        }
        if (args.hasAny()) {
            args.getAsOrDefault(Integer.class, 1);
        }
        if (args.hasAtMostOne()) {
            return args.tabCompleteDatatype(ItemById.INSTANCE);
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Get or craft items";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The get command recursively collects and crafts items using Baritone processes.",
                "",
                "Usage:",
                "> get wooden_pickaxe - Mines logs, crafts planks/sticks/table, and crafts a wooden pickaxe.",
                "> get 4 stick - Gets at least four sticks.",
                "> get [dirt3,wooden_pickaxe,stone_sword4] - Gets each requested item in order.",
                "",
                "Batch entries support item<count>, item=<count>, itemx<count>, and <count>xitem.",
                "Exact item IDs are checked first, so music_disc_13 means one music_disc_13."
        );
    }

    private Map<Item, Integer> parseBatch(String raw, ICommandArgument arg) throws CommandInvalidTypeException {
        if (!raw.endsWith("]")) {
            throw new CommandInvalidTypeException(arg, "item list like [dirt3,wooden_pickaxe,stone_sword4]");
        }
        String inner = raw.substring(1, raw.length() - 1).trim();
        if (inner.isEmpty()) {
            throw new CommandInvalidTypeException(arg, "at least one item in the list");
        }
        Map<Item, Integer> requests = new LinkedHashMap<>();
        for (String entry : inner.split(",")) {
            ItemRequest request = parseEntry(entry.trim(), arg);
            requests.merge(request.item, request.quantity, Math::max);
        }
        return requests;
    }

    private ItemRequest parseEntry(String rawEntry, ICommandArgument arg) throws CommandInvalidTypeException {
        if (rawEntry.isEmpty()) {
            throw new CommandInvalidTypeException(arg, "non-empty item entries");
        }

        Optional<Item> exact = lookupItem(rawEntry);
        if (exact.isPresent()) {
            return new ItemRequest(exact.get(), 1);
        }

        int equals = rawEntry.lastIndexOf('=');
        if (equals > 0 && equals < rawEntry.length() - 1) {
            return parseSplit(rawEntry.substring(0, equals), rawEntry.substring(equals + 1), arg);
        }

        int star = rawEntry.lastIndexOf('*');
        if (star > 0 && star < rawEntry.length() - 1) {
            return parseSplit(rawEntry.substring(0, star), rawEntry.substring(star + 1), arg);
        }

        int x = rawEntry.lastIndexOf('x');
        if (x > 0 && x < rawEntry.length() - 1 && isPositiveInteger(rawEntry.substring(x + 1))) {
            Optional<Item> item = lookupItem(rawEntry.substring(0, x));
            if (item.isPresent()) {
                return new ItemRequest(item.get(), parseQuantity(rawEntry.substring(x + 1), arg));
            }
        }
        if (x > 0 && isPositiveInteger(rawEntry.substring(0, x))) {
            Optional<Item> item = lookupItem(rawEntry.substring(x + 1));
            if (item.isPresent()) {
                return new ItemRequest(item.get(), parseQuantity(rawEntry.substring(0, x), arg));
            }
        }

        int split = rawEntry.length();
        while (split > 0 && Character.isDigit(rawEntry.charAt(split - 1))) {
            split--;
        }
        if (split > 0 && split < rawEntry.length()) {
            Optional<Item> item = lookupItem(rawEntry.substring(0, split));
            if (item.isPresent()) {
                return new ItemRequest(item.get(), parseQuantity(rawEntry.substring(split), arg));
            }
        }

        throw new CommandInvalidTypeException(arg, "known item or item/count entry", rawEntry);
    }

    private ItemRequest parseSplit(String itemText, String quantityText, ICommandArgument arg) throws CommandInvalidTypeException {
        Optional<Item> item = lookupItem(itemText.trim());
        if (item.isEmpty()) {
            throw new CommandInvalidTypeException(arg, "known item", itemText);
        }
        return new ItemRequest(item.get(), parseQuantity(quantityText.trim(), arg));
    }

    private Optional<Item> lookupItem(String text) {
        try {
            return BuiltInRegistries.ITEM.getOptional(new ResourceLocation(text.trim()));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private int parseQuantity(String text, ICommandArgument arg) throws CommandInvalidTypeException {
        if (!isPositiveInteger(text)) {
            throw new CommandInvalidTypeException(arg, "positive item count", text);
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            throw new CommandInvalidTypeException(arg, "positive item count", text, ex);
        }
    }

    private boolean isPositiveInteger(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return !text.chars().allMatch(ch -> ch == '0');
    }

    private Stream<String> tabCompleteBatch(String raw) {
        int open = raw.lastIndexOf('[');
        int comma = raw.lastIndexOf(',');
        int start = Math.max(open, comma) + 1;
        String prefix = raw.substring(start).trim();
        return BuiltInRegistries.ITEM.keySet()
                .stream()
                .map(ResourceLocation::toString)
                .filter(id -> id.startsWith(prefix) || id.substring(id.indexOf(':') + 1).startsWith(prefix))
                .sorted();
    }

    private String describeBatch(Map<Item, Integer> requests) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Item, Integer> entry : requests.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(entry.getValue()).append(' ').append(BuiltInRegistries.ITEM.getKey(entry.getKey()));
        }
        return builder.toString();
    }

    private static final class ItemRequest {
        private final Item item;
        private final int quantity;

        private ItemRequest(Item item, int quantity) {
            this.item = item;
            this.quantity = quantity;
        }
    }
}
