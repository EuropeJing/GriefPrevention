/*
 * This file is part of GriefPrevention, licensed under the MIT License (MIT).
 *
 * Copyright (c) Ryan Hamshire
 * Copyright (c) bloodmc
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package me.ryanhamshire.griefprevention.command;

import com.google.common.collect.ImmutableSet;
import me.ryanhamshire.griefprevention.GPPlayerData;
import me.ryanhamshire.griefprevention.GriefPreventionPlugin;
import me.ryanhamshire.griefprevention.claim.ClaimsMode;
import me.ryanhamshire.griefprevention.claim.GPClaim;
import me.ryanhamshire.griefprevention.claim.GPClaimManager;
import me.ryanhamshire.griefprevention.event.GPDeleteClaimEvent;
import me.ryanhamshire.griefprevention.message.Messages;
import me.ryanhamshire.griefprevention.message.TextMode;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.NamedCause;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;

import java.util.UUID;

public class CommandClaimAbandon implements CommandExecutor {

    private boolean deleteTopLevelClaim;

    public CommandClaimAbandon(boolean deleteTopLevelClaim) {
        this.deleteTopLevelClaim = deleteTopLevelClaim;
    }

    @Override
    public CommandResult execute(CommandSource src, CommandContext ctx) {
        Player player;
        try {
            player = GriefPreventionPlugin.checkPlayer(src);
        } catch (CommandException e) {
            src.sendMessage(e.getText());
            return CommandResult.success();
        }
        // which claim is being abandoned?
        GPPlayerData playerData = GriefPreventionPlugin.instance.dataStore.getOrCreatePlayerData(player.getWorld(), player.getUniqueId());
        GPClaim claim = GriefPreventionPlugin.instance.dataStore.getClaimAt(player.getLocation(), true);
        UUID ownerId = claim.getOwnerUniqueId();
        if (claim.parent != null) {
            ownerId = claim.parent.getOwnerUniqueId();
        }
        if (claim.isWildernessClaim()) {
            GriefPreventionPlugin.sendMessage(player, TextMode.Instr, Messages.AbandonClaimMissing);
            return CommandResult.success();
        } else if (claim.allowEdit(player) != null || (!claim.isAdminClaim() && !player.getUniqueId().equals(ownerId))) {
            // verify ownership
            GriefPreventionPlugin.sendMessage(player, TextMode.Err, Messages.NotYourClaim);
            return CommandResult.success();
        }

        // warn if has children and we're not explicitly deleting a top level claim
        else if (claim.children.size() > 0 && !deleteTopLevelClaim) {
            GriefPreventionPlugin.sendMessage(player, TextMode.Instr, Messages.DeleteTopLevelClaim);
            return CommandResult.empty();
        } else {
            GPDeleteClaimEvent.Abandon event = new GPDeleteClaimEvent.Abandon(claim, Cause.of(NamedCause.source(src)));
            Sponge.getEventManager().post(event);
            if (event.isCancelled()) {
                player.sendMessage(Text.of(TextColors.RED, event.getMessage().orElse(Text.of("Could not abandon claim. A plugin has denied it."))));
                return CommandResult.success();
            }

            // delete it
            GPClaimManager claimManager = GriefPreventionPlugin.instance.dataStore.getClaimWorldManager(player.getWorld().getProperties());
            claimManager.deleteClaim(claim);
            claim.removeSurfaceFluids(null);
            // remove all context permissions
            player.getSubjectData().clearPermissions(ImmutableSet.of(claim.getContext()));
            GriefPreventionPlugin.GLOBAL_SUBJECT.getSubjectData().clearPermissions(ImmutableSet.of(claim.getContext()));

            // if in a creative mode world, restore the claim area
            if (GriefPreventionPlugin.instance.claimModeIsActive(claim.getLesserBoundaryCorner().getExtent().getProperties(), ClaimsMode.Creative)) {
                GriefPreventionPlugin.addLogEntry(
                        player.getName() + " abandoned a claim @ " + GriefPreventionPlugin.getfriendlyLocationString(claim.getLesserBoundaryCorner()));
                GriefPreventionPlugin.sendMessage(player, TextMode.Warn, Messages.UnclaimCleanupWarning);
                GriefPreventionPlugin.instance.restoreClaim(claim, 20L * 60 * 2);
            }

            // this prevents blocks being gained without spending adjust claim blocks when abandoning a top level claim
            if (!claim.isSubdivision() && !claim.isAdminClaim()) {
                int newAccruedClaimCount = playerData.getAccruedClaimBlocks() - ((int) Math.ceil(claim.getArea() * (1 - playerData.optionAbandonReturnRatio)));
                playerData.setAccruedClaimBlocks(newAccruedClaimCount);
            }

            // tell the player how many claim blocks he has left
            int remainingBlocks = playerData.getRemainingClaimBlocks();
            GriefPreventionPlugin.sendMessage(player, TextMode.Success, Messages.AbandonSuccess, String.valueOf(remainingBlocks));
            // revert any current visualization
            playerData.revertActiveVisual(player);
            playerData.warnedAboutMajorDeletion = false;
        }

        return CommandResult.success();
    }
}
