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

    public CaskaraCommand() {
        super("caskara", "Caskara Database Management");
        this.setPermissionGroups("admin"); // Restricts to OP / Admin
        
        this.addSubCommand(new StatsCommand());
        this.addSubCommand(new VacuumCommand());
        this.addSubCommand(new BackupCommand());
        this.addSubCommand(new AutoBackupCommand());
        this.addSubCommand(new DumpCommand());
        this.addSubCommand(new ScanCommand());
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
        ctx.sendMessage(Message.raw("Caskara Commands: /caskara <stats|vacuum|backup|autobackup|dump|scan>"));
        return CompletableFuture.completedFuture(null);
    }

    private static void sendResponses(CommandContext ctx, List<String> responses) {
        for (String resp : responses) {
            ctx.sendMessage(Message.raw(resp));
        }
    }

    public static class StatsCommand extends AbstractCommand {
        public StatsCommand() { super("stats", "View Caskara stats"); }
        @Override protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
            sendResponses(ctx, CaskaraAdminLogic.getStats());
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class VacuumCommand extends AbstractCommand {
        public VacuumCommand() { super("vacuum", "Run vacuum on all databases"); }
        @Override protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
            ctx.sendMessage(Message.raw("Starting VACUUM on all databases..."));
            sendResponses(ctx, CaskaraAdminLogic.runVacuum());
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class BackupCommand extends AbstractCommand {
        public BackupCommand() { super("backup", "Run a global backup immediately"); }
        @Override protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
            ctx.sendMessage(Message.raw("Starting Global Backup..."));
            sendResponses(ctx, CaskaraAdminLogic.runBackup());
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class AutoBackupCommand extends AbstractCommand {
        private final RequiredArg<String> hoursArg;
        public AutoBackupCommand() {
            super("autobackup", "Toggle auto backup interval (0 to disable)");
            this.hoursArg = this.withRequiredArg("hours", "Hours", ArgTypes.STRING);
        }
        @Override protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
            sendResponses(ctx, CaskaraAdminLogic.toggleAutoBackup(ctx.get(hoursArg)));
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class DumpCommand extends AbstractCommand {
        private final RequiredArg<String> targetArg;
        public DumpCommand() {
            super("dump", "Dump entity to log");
            this.targetArg = this.withRequiredArg("target", "Target ID", ArgTypes.STRING);
        }
        @Override protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
            sendResponses(ctx, CaskaraAdminLogic.dumpEntity(ctx.get(targetArg)));
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class ScanCommand extends AbstractCommand {
        private final RequiredArg<String> packageArg;
        public ScanCommand() {
            super("scan", "Scan a package for @CaskaraEntity");
            this.packageArg = this.withRequiredArg("package", "Package name", ArgTypes.STRING);
        }
        @Override protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
            sendResponses(ctx, CaskaraAdminLogic.scanPackage(ctx.get(packageArg)));
            return CompletableFuture.completedFuture(null);
        }
    }
}
