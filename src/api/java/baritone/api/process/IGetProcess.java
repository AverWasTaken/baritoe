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

package baritone.api.process;

import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Map;

public interface IGetProcess extends IBaritoneProcess {

    /**
     * Begin collecting or crafting an item.
     *
     * @param item     The item to obtain
     * @param quantity The desired total inventory count
     */
    void get(Item item, int quantity);

    default void get(Item item) {
        get(item, 1);
    }

    /**
     * Begin collecting or crafting multiple items in order.
     *
     * @param items Ordered item-to-desired-total-count requests
     */
    void get(Map<Item, Integer> items);

    default void cancel() {
        onLostControl();
    }

    /**
     * @return User-facing status lines suitable for rendering in a HUD.
     */
    default List<String> statusLines() {
        return List.of(displayName());
    }
}
