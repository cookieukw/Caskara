package com.cookie.caskara.commands;


import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;

import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import java.util.List;
import java.util.Collections;

public class CaskaraCommand extends AbstractCommand {

    private final RequiredArg<String> actionArg;
    private final OptionalArg<String> targetArg;

    public CaskaraCommand() {
        super("caskara", "Caskara Database Management");
        this.setPermissionGroups("admin"); // Restricts to OP / Admin
        
        this.actionArg = this.withRequiredArg("action", "stats | vacuum | dump | scan | backup | autobackup", ArgTypes.STRING);
        this.targetArg = this.withOptionalArg("target", "Target ID/Package or Hours", ArgTypes.STRING);
    }

    @Override
    @SuppressWarnings({"null"})
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
        String action = ctx.get(actionArg).toLowerCase();
        String target = ctx.provided(targetArg) ? ctx.get(targetArg) : null;
        List<String> responses;

        switch (action) {
            case "stats":
                responses = CaskaraAdminLogic.getStats();
                break;
            case "vacuum":
                ctx.sendMessage(Message.raw("Starting VACUUM on all databases..."));
                responses = CaskaraAdminLogic.runVacuum();
                break;
            case "backup":
                ctx.sendMessage(Message.raw("Starting Global Backup..."));
                responses = CaskaraAdminLogic.runBackup();
                break;
            case "autobackup":
                responses = CaskaraAdminLogic.toggleAutoBackup(target);
                break;
            case "dump":
                responses = CaskaraAdminLogic.dumpEntity(target);
                break;
            case "scan":
                responses = CaskaraAdminLogic.scanPackage(target);
                break;
            default:
                responses = Collections.singletonList("Unknown action: " + action + ". Use: stats, vacuum, dump, scan, backup, or autobackup.");
                break;
        }

        for (String resp : responses) {
            ctx.sendMessage(Message.raw(resp));
        }

        return CompletableFuture.completedFuture(null);
    }
}
