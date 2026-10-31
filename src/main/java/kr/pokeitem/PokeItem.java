package kr.pokeitem;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.Gender;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.Unit;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /pokeitem <slot> turns a party pokemon into an item (Cobblemon's pokemon model item, so clients without this mod
 * show it too). Right clicking the item gives the pokemon back. Every item carries a token that can only be
 * redeemed once, so copies (e.g. creative middle click) can not duplicate the pokemon.
 */
public class PokeItem implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("poke-item");
    private static final String KEY = "pokeitem";
    private static final Style PLAIN = Style.EMPTY.withItalic(false);

    private static Set<String> redeemed;
    private static Path redeemedFile;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("pokeitem")
                .executes(ctx -> list(ctx.getSource().getPlayerOrThrow()))
                .then(CommandManager.argument("slot", IntegerArgumentType.integer(1, 6))
                    .executes(ctx -> toItem(ctx.getSource().getPlayerOrThrow(), IntegerArgumentType.getInteger(ctx, "slot"))))));

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);

            if (data(stack) == null) {
                return TypedActionResult.pass(stack);
            }

            if (player instanceof ServerPlayerEntity serverPlayer) {
                redeem(serverPlayer, stack);
            }

            return TypedActionResult.success(stack);
        });

        // dropped pokemon items do not despawn and can not be destroyed (fire, lava, cactus, explosions)
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof ItemEntity item && data(item.getStack()) != null) {
                item.setNeverDespawn();
                item.setInvulnerable(true);
            }
        });
    }

    private static NbtCompound data(ItemStack stack) {
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);

        if (custom == null) {
            return null;
        }

        NbtCompound nbt = custom.copyNbt();
        return nbt.contains(KEY) ? nbt.getCompound(KEY) : null;
    }

    private static int list(ServerPlayerEntity player) {
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        player.sendMessage(Text.literal("[포켓몬 아이템] /pokeitem <번호> 로 파티 포켓몬을 아이템으로 바꿉니다.").formatted(Formatting.YELLOW));

        for (int i = 0; i < 6; i++) {
            Pokemon pokemon = party.get(i);

            if (pokemon != null) {
                player.sendMessage(Text.literal("  " + (i + 1) + ". ").append(pokemon.getDisplayName(false))
                    .append(" Lv." + pokemon.getLevel() + (pokemon.getShiny() ? " ★" : "")));
            }
        }

        return 1;
    }

    private static int toItem(ServerPlayerEntity player, int slot) {
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return fail(player, "배틀 중에는 할 수 없습니다.");
        }

        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        Pokemon pokemon = party.get(slot - 1);

        if (pokemon == null) {
            return fail(player, slot + "번 자리에 포켓몬이 없습니다. (/pokeitem 으로 목록 보기)");
        }

        if (party.occupied() <= 1) {
            return fail(player, "파티의 마지막 한 마리는 아이템으로 바꿀 수 없습니다.");
        }

        if (pokemon.getEntity() != null) {
            pokemon.recall();
        }

        MinecraftServer server = player.getServer();
        NbtCompound saved = pokemon.saveToNBT(server.getRegistryManager(), new NbtCompound());

        if (!party.remove(pokemon)) {
            return fail(player, "파티에서 꺼내지 못했습니다.");
        }

        ItemStack stack = createItem(pokemon, saved);

        if (!player.getInventory().insertStack(stack)) {
            player.dropItem(stack, false);
            player.sendMessage(Text.literal("[포켓몬 아이템] 인벤토리가 꽉 차서 발밑에 떨어뜨렸습니다.").formatted(Formatting.YELLOW));
        }

        player.sendMessage(Text.literal("[포켓몬 아이템] ").formatted(Formatting.GREEN).append(pokemon.getDisplayName(false))
            .append(" 을(를) 아이템으로 바꿨습니다. 들고 우클릭하면 다시 받습니다.").formatted(Formatting.GREEN));
        LOGGER.info("{} turned {} (Lv.{}, {}) into an item", player.getGameProfile().getName(),
            pokemon.getSpecies().getName(), pokemon.getLevel(), pokemon.getUuid());
        return 1;
    }

    private static ItemStack createItem(Pokemon pokemon, NbtCompound saved) {
        ItemStack stack = PokemonItem.from(pokemon);
        NbtCompound custom = new NbtCompound();
        NbtCompound data = saved.copy();
        data.putString("token", UUID.randomUUID().toString());
        custom.put(KEY, data);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(custom));
        stack.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        stack.set(DataComponentTypes.FIRE_RESISTANT, Unit.INSTANCE);

        MutableText name = Text.empty().setStyle(PLAIN.withColor(pokemon.getShiny() ? Formatting.GOLD : Formatting.WHITE))
            .append(pokemon.getDisplayName(false)).append(" Lv." + pokemon.getLevel());

        if (pokemon.getGender() == Gender.MALE) {
            name.append(Text.literal(" ♂").formatted(Formatting.BLUE));
        } else if (pokemon.getGender() == Gender.FEMALE) {
            name.append(Text.literal(" ♀").formatted(Formatting.RED));
        }

        if (pokemon.getShiny()) {
            name.append(Text.literal(" ★").formatted(Formatting.GOLD));
        }

        stack.set(DataComponentTypes.CUSTOM_NAME, name);

        List<Text> lore = new ArrayList<>();
        lore.add(line("종류: ", pokemon.getSpecies().getTranslatedName()));
        lore.add(line("성격: ", Text.translatable(pokemon.getNature().getDisplayName())));
        lore.add(line("특성: ", Text.translatable(pokemon.getAbility().getDisplayName())));
        lore.add(line("개체값: ", Text.literal(pokemon.getIvs().getOrDefault(Stats.HP) + "/" + pokemon.getIvs().getOrDefault(Stats.ATTACK)
            + "/" + pokemon.getIvs().getOrDefault(Stats.DEFENCE) + "/" + pokemon.getIvs().getOrDefault(Stats.SPECIAL_ATTACK)
            + "/" + pokemon.getIvs().getOrDefault(Stats.SPECIAL_DEFENCE) + "/" + pokemon.getIvs().getOrDefault(Stats.SPEED))));
        MutableText moves = Text.empty();

        for (Move move : pokemon.getMoveSet().getMoves()) {
            if (!moves.getSiblings().isEmpty()) {
                moves.append(", ");
            }

            moves.append(move.getDisplayName());
        }

        lore.add(line("기술: ", moves));

        if (pokemon.getOriginalTrainerName() != null) {
            lore.add(line("어버이: ", Text.literal(pokemon.getOriginalTrainerName())));
        }

        lore.add(Text.literal("들고 우클릭하면 내 포켓몬으로 받습니다").setStyle(PLAIN.withColor(Formatting.YELLOW)));
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    private static Text line(String label, Text value) {
        return Text.literal(label).setStyle(PLAIN.withColor(Formatting.GRAY))
            .append(value.copy().setStyle(PLAIN.withColor(Formatting.WHITE)));
    }

    private static void redeem(ServerPlayerEntity player, ItemStack stack) {
        NbtCompound data = data(stack);
        String token = data.getString("token");

        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            fail(player, "배틀 중에는 받을 수 없습니다.");
            return;
        }

        if (!token.isEmpty() && isRedeemed(player.getServer(), token)) {
            stack.decrement(1);
            fail(player, "이미 받은 포켓몬 아이템입니다 (복사본). 아이템을 없앴습니다.");
            return;
        }

        Pokemon pokemon;

        try {
            NbtCompound nbt = data.copy();
            nbt.remove("token");
            pokemon = Pokemon.Companion.loadFromNBT(player.getServer().getRegistryManager(), nbt);
        } catch (Exception e) {
            LOGGER.error("could not read the pokemon of an item", e);
            fail(player, "포켓몬 정보를 읽지 못했습니다. 아이템은 그대로 둡니다.");
            return;
        }

        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        boolean toParty = party.add(pokemon);

        if (!toParty) {
            PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);

            if (!pc.add(pokemon)) {
                fail(player, "파티와 PC가 모두 꽉 찼습니다.");
                return;
            }
        }

        markRedeemed(player.getServer(), token);
        stack.decrement(1);
        player.sendMessage(Text.literal("[포켓몬 아이템] ").formatted(Formatting.GREEN).append(pokemon.getDisplayName(false))
            .append(toParty ? " 을(를) 파티로 받았습니다." : " 을(를) PC로 받았습니다 (파티가 꽉 참).").formatted(Formatting.GREEN));
        LOGGER.info("{} received {} (Lv.{}, {}) from an item", player.getGameProfile().getName(),
            pokemon.getSpecies().getName(), pokemon.getLevel(), pokemon.getUuid());
    }

    private static int fail(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal("[포켓몬 아이템] " + message).formatted(Formatting.RED));
        return 0;
    }

    private static synchronized boolean isRedeemed(MinecraftServer server, String token) {
        loadRedeemed(server);
        return redeemed.contains(token);
    }

    private static synchronized void markRedeemed(MinecraftServer server, String token) {
        if (token.isEmpty()) {
            return;
        }

        loadRedeemed(server);
        redeemed.add(token);

        try {
            Files.writeString(redeemedFile, token + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("could not write {}", redeemedFile, e);
        }
    }

    /** Redeemed tokens live in the world folder, so they move with the world. */
    private static void loadRedeemed(MinecraftServer server) {
        Path file = server.getSavePath(WorldSavePath.ROOT).resolve("pokeitem-redeemed.txt");

        if (file.equals(redeemedFile)) {
            return;
        }

        redeemedFile = file;
        redeemed = new HashSet<>();

        try {
            if (Files.exists(file)) {
                Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(String::trim).filter(s -> !s.isEmpty())
                    .forEach(redeemed::add);
            }
        } catch (IOException e) {
            LOGGER.error("could not read {}", file, e);
        }
    }
}
