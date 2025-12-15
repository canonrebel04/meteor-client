
package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.storage.StorageManager;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.command.CommandSource;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class StorageCommand extends Command {
    public StorageCommand() {
        super("storage", "Manage storage indexing.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("debug").executes(ctx -> {
            if (!Utils.canUpdate()) {
                error("Not in a world.");
                return SINGLE_SUCCESS;
            }
            
            int count = StorageManager.get().getAll().size();
            info("Indexed %d containers.", count);
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("find")
            .then(argument("item", StringArgumentType.string())
                .executes(ctx -> {
                    String itemId = ctx.getArgument("item", String.class);
                    
                    if (!Utils.canUpdate()) {
                        error("Not in a world.");
                        return SINGLE_SUCCESS;
                    }
                    
                    Item item = Registries.ITEM.get(Identifier.of(itemId));
                    if (item == null) {
                        error("Item '%s' not found.", itemId);
                        return SINGLE_SUCCESS;
                    }
                    
                    BlockPos pos = StorageManager.get().findItem(item);
                    if (pos == null) {
                        error("Item '%s' not found in any indexed containers.", itemId);
                    } else {
                        info("Item '%s' found at (%d, %d, %d).", itemId, pos.getX(), pos.getY(), pos.getZ());
                    }
                    return SINGLE_SUCCESS;
                })
            )
        );
        
        builder.then(literal("duplicates").executes(ctx -> {
            if (!Utils.canUpdate()) {
                error("Not in a world.");
                return SINGLE_SUCCESS;
            }
            
            java.util.List<BlockPos> dups = StorageManager.get().getDuplicates();
            if (dups.isEmpty()) {
                info("No duplicate inventories found.");
            } else {
                info("Found %d containers with duplicate content:", dups.size());
                for (BlockPos pos : dups) {
                    info("- (%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ());
                }
            }
            return SINGLE_SUCCESS;
        }));
    }
}
