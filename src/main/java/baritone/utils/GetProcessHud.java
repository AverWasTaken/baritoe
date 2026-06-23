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

package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IGetProcess;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;

import java.util.List;

public final class GetProcessHud {

    private static final int BACKGROUND = 0xAA101018;
    private static final int ACCENT = 0xFFE056FD;
    private static final int TITLE = 0xFFFFF7FF;
    private static final int TEXT = 0xFFD7D7E6;

    private GetProcessHud() {}

    public static void render(PoseStack stack) {
        if (!BaritoneAPI.getSettings().renderGetProcessHud.value) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.options.hideGui || minecraft.options.renderDebug) {
            return;
        }

        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null || !baritone.getGetProcess().isActive()) {
            return;
        }

        IGetProcess process = baritone.getGetProcess();
        List<String> lines = process.statusLines();
        if (lines.isEmpty()) {
            return;
        }

        Font font = minecraft.font;
        int x = 4;
        int y = 4;
        int padding = 4;
        int lineStep = font.lineHeight + 2;
        int width = lines.stream().mapToInt(font::width).max().orElse(0);
        int height = padding * 2 + lineStep * lines.size() - 2;

        GuiComponent.fill(stack, x - 1, y - 1, x + width + padding * 2 + 1, y + height + 1, BACKGROUND);
        GuiComponent.fill(stack, x - 1, y - 1, x + 1, y + height + 1, ACCENT);

        int textX = x + padding;
        int textY = y + padding;
        for (int i = 0; i < lines.size(); i++) {
            font.drawShadow(stack, lines.get(i), textX, textY + i * lineStep, i == 0 ? TITLE : TEXT);
        }
    }
}
