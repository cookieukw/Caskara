package com.cookie.caskara.commands;

import com.cookie.caskara.Caskara;
import com.cookie.caskara.db.Shell;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

public class CaskaraCommand extends AbstractCommand {

    private final RequiredArg<String> actionArg;
    private final OptionalArg<String> targetArg;

    public CaskaraCommand() {
        super("caskara", "Caskara Database Management");
        this.setPermissionGroups("admin"); // Restricts to OP / Admin
        
        this.actionArg = this.withRequiredArg("action", "stats | vacuum | dump | scan", ArgTypes.STRING);
        this.targetArg = this.withOptionalArg("target", "Entity ID (for dump) or package (for scan)", ArgTypes.STRING);
    }

    @Override
    @SuppressWarnings({"null"})
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
        String action = ctx.get(actionArg).toLowerCase();
        String target = ctx.provided(targetArg) ? ctx.get(targetArg) : null;

        switch (action) {
            case "stats":
                handleStats(ctx);
                break;
            case "vacuum":
                handleVacuum(ctx);
                break;
            case "dump":
                handleDump(ctx, target);
                break;
            case "scan":
                handleScan(ctx, target);
                break;
            default:
                ctx.sendMessage(Message.raw("Unknown action: " + action + ". Use: stats, vacuum, dump, or scan."));
                break;
        }

        return CompletableFuture.completedFuture(null);
    }

    private void handleStats(CommandContext ctx) {
        long totalQueries = 0;
        long totalHits = 0;
        long totalMisses = 0;
        int shellCount = Caskara.getShells().size();

        for (Shell shell : Caskara.getShells().values()) {
            totalQueries += shell.getStats().getTotalQueries();
            totalHits += shell.getStats().getCacheHits();
            totalMisses += shell.getStats().getCacheMisses();
        }

        String msg = String.format("Caskara Stats: %d active shells | %d queries | Cache: %d hits, %d misses",
                shellCount, totalQueries, totalHits, totalMisses);
        
        ctx.sendMessage(Message.raw(msg));
        System.out.println("[Caskara] " + msg);
    }

    private void handleVacuum(CommandContext ctx) {
        ctx.sendMessage(Message.raw("Starting VACUUM on all databases..."));
        long start = System.currentTimeMillis();
        
        int count = 0;
        for (Shell shell : Caskara.getShells().values()) {
            try {
                Connection conn = shell.getConnection();
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("VACUUM");
                }
                count++;
            } catch (Exception e) {
                System.err.println("[Caskara] Failed to vacuum shell: " + e.getMessage());
            }
        }
        
        long took = System.currentTimeMillis() - start;
        ctx.sendMessage(Message.raw("VACUUM completed on " + count + " databases in " + took + "ms."));
    }

    private void handleDump(CommandContext ctx, String id) {
        if (id == null) {
            ctx.sendMessage(Message.raw("Usage: /caskara dump <entity_id>"));
            return;
        }

        boolean found = false;
        for (Shell shell : Caskara.getShells().values()) {
            try {
                Connection conn = shell.getConnection();
                try (PreparedStatement pstmt = conn.prepareStatement("SELECT type, json FROM elements WHERE id = ?")) {
                    pstmt.setString(1, id);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            String type = rs.getString("type");
                            String json = rs.getString("json");
                            
                            System.out.println("[CaskaraDump] === DUMP FOR ID: " + id + " ===");
                            System.out.println("[CaskaraDump] Type: " + type);
                            System.out.println("[CaskaraDump] JSON: " + json);
                            System.out.println("[CaskaraDump] ===========================");
                            
                            ctx.sendMessage(Message.raw("Dumped entity " + id + " to Server Console!"));
                            found = true;
                            break; // Stop searching other shells once found
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[Caskara] Error during dump search: " + e.getMessage());
            }
        }

        if (!found) {
            ctx.sendMessage(Message.raw("Entity ID '" + id + "' not found in any database."));
        }
    }

    private void handleScan(CommandContext ctx, String pkg) {
        if (pkg == null) {
            ctx.sendMessage(Message.raw("Usage: /caskara scan <package.name>"));
            return;
        }
        ctx.sendMessage(Message.raw("Scanning package " + pkg + " for CaskaraEntities..."));
        try {
            Caskara.scanPackage(pkg);
            ctx.sendMessage(Message.raw("Scan complete."));
        } catch (Exception e) {
            ctx.sendMessage(Message.raw("Scan failed! See console."));
            System.err.println("[Caskara] Scan failed: " + e.getMessage());
        }
    }
}
