package plugin.commands;

import arc.Events;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Strings;
import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.maps.Map;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import plugin.models.PlayerData;
import plugin.models.PlayerDataCollection;
import useful.Bundle;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

import static arc.util.Strings.canParseInt;
import static mindustry.Vars.mods;
import static plugin.ConfigJson.discordUrl;
import static plugin.Plugin.players;
import static plugin.Plugin.servers;
import static plugin.commands.Menus.achMenu;
import static plugin.commands.history.History.historyPlayers;
import static plugin.functions.Other.statsMenu;
import static plugin.utils.Utilities.*;

public class MainCommands {
    public static AtomicInteger votes = new AtomicInteger(0);
    public static Seq<Player> votedPlayer = new Seq<>();
    public static boolean isVoting = false;

    public static void loadClientCommands(CommandHandler handler) {
        handler.<Player>register("announce", "<text...>", "calls an announce", (args, player) -> {
            if (!player.admin) {
                player.sendMessage("[red]You do not have enough permissions!");
            } else {
                Call.announce(args[0]);
            }
        });
        handler.<Player>register("gameover", "Executes a gameover event", (args, player) -> {
            if (!player.admin) {
                player.sendMessage("[red]You do not have enough permissions!");
            } else {
                Events.fire(new EventType.GameOverEvent(Team.derelict));
            }
        });
        handler.<Player>register("list", "Lists all players on the server", (args, player) -> {
            StringBuilder list = new StringBuilder();
            for (Player plr : Groups.player) {
                PlayerData data = new PlayerData(plr);
                if (data.isExist()) {
                    list.append(plr.name()).append("; [white]ID: ").append(data.getId()).append("\n");
                }
            }
            player.sendMessage(String.valueOf(list));
        });
        handler.<Player>register("js", "<code...>", "Execute JavaScript code.", (args, player) -> {
            PlayerData data = new PlayerData(player);
            if (player.admin() && data.getRank().hasJS()) {
                try {
                    String output = mods.getScripts().runConsole(args[0]);
                    player.sendMessage("> " + ("[#ff341c]" + output));
                } catch (Exception e) {
                    player.sendMessage("Error! " + e);
                }
            } else {
                player.sendMessage("[scarlet]You must be console to use this command.");
            }
        });
        handler.<Player>register("maps", "[page]", "List all maps", (args, player) -> {
            StringBuilder list = new StringBuilder();
            int page;
            if (Arrays.stream(args).toList().isEmpty()) {
                page = 0;
            } else {
                if (!canParseInt(args[0])) {
                    player.sendMessage("[red]Page must be number!");
                    return;
                }
                page = Integer.parseInt(args[0]);
            }
            int mapsPerPage = 10;
            Seq<Map> maps = getMaps();
            maps.list().stream().skip(page * 10L).limit(mapsPerPage + (page * 10L)).forEach(
                    map -> list.append(map.name()).append("[white], by ").append(map.author()).append("\n")
            );
            if (!String.valueOf(list).contains("by")) {
                player.sendMessage("[red]No maps detected!");
                return;
            }
            player.sendMessage(String.valueOf(list));
        });
        handler.<Player>register("rtv", "<map...>", "Rock the vote to change map!", (args, player) -> {
            final int[] votesRequired = new int[1];
            AtomicInteger time = new AtomicInteger(60);
            votesRequired[0] = (int) Math.ceil((double) Groups.player.size() / 2);
            Timer timer = new Timer();
            if (isVoting) {
                player.sendMessage("Vote is already running!");
                return;
            }
            Map choosedMap = Vars.maps.customMaps().find(map -> Strings.stripColors(map.name()).contains(args[0]));
            if (choosedMap == null) {
                player.sendMessage("Could not find that map!");
                return;
            }
            Call.sendMessage(player.name() + "[#e7e7e7] Started vote for map " + choosedMap.plainName() + "[#e7e7e7] -> " + votes.get() + "/" + votesRequired[0] + ", y/n to vote");
            isVoting = true;
            timer.schedule((new TimerTask() {
                @Override
                public void run() {
                    time.getAndAdd(-1);
                    votesRequired[0] = (int) Math.ceil((double) Groups.player.size() / 2);
                    if (votes.get() >= votesRequired[0]) {
                        voteSuccess(choosedMap);
                        isVoting = false;
                        timer.cancel();
                    }
                    if (time.get() < 0) {
                        voteCanceled();
                        isVoting = false;
                        timer.cancel();
                    }
                }
            }), 0, 1000);
        });
        handler.<Player>register("discord", "Link to our discord!", (args, player) -> {
            Call.openURI(player.con, discordUrl);
        });
        handler.<Player>register("stats", "[player...]", "Get stats of player or yourself", (args, player) -> {
            if (args.length == 0) {
                statsMenu(player, player);
                return;
            }
            Player plr = findPlayerByName(args[0]);
            if (plr == null) {
                player.sendMessage("Could not find that player!");
                return;
            }
            statsMenu(player, plr);
        });
        handler.<Player>register("history", "Enables/Disables history", (args, player) -> {
            if (historyPlayers.contains(player.uuid())) {
                historyPlayers.remove(player.uuid());
                player.sendMessage("[red]History has been disabled!");
                Call.hideHudText(player.con());
                return;
            }
            historyPlayers.add(player.uuid());
            player.sendMessage("[green]History has been enabled!");
        });
        handler.<Player>register("joinmessage", "<message...>", "Makes custom join message! @ -> your name. Make sure this message wont break any rule!", (args, player) -> {
            PlayerData data = new PlayerData(player);
            if (args[0].length() >= 45) {
                player.sendMessage("Too much symbols! Limit is 45");
            } else {
                data.setJoinMessage(args[0]);
                player.sendMessage("[green]Changed your join message!");
            }
        });
        handler.<Player>register("leaderboard", "<playtime/wins>", "Shows leaderboard!", (args, player) -> {
            StringBuilder list = new StringBuilder();
            if (Objects.equals(args[0], "playtime")) {
                list.append("[orange]Playtime leaderboard: \n");
                FindIterable<PlayerDataCollection> sort = players.find().sort(new BasicDBObject("playtime", -1)).limit(10);
                for (PlayerDataCollection data : sort) {
                    int playtime = data.playtime;
                    list.append(data.rawName + "[white]: " + Bundle.formatDuration(Duration.ofMinutes(playtime)) + "\n");
                }
                player.sendMessage(list.toString());
            } else {
                player.sendMessage("[red]Invalid type!");
            }
        });
        handler.<Player>register("achievements", "Views your achievements", (args, player) -> {
            achMenu(player);
        });
        handler.<Player>register("serverhop", "<server>", "Hops to server", (args, parameter) -> {
            JSONArray array = (JSONArray) servers.get("servers");
            Seq<Server> servers = new Seq<>();
            for (Object object : array) {
                JSONObject jsonObject = (JSONObject) object;
                Server serv = new Server((String) jsonObject.get("servername"), (String) jsonObject.get("ip"), (Long) jsonObject.get("port"));
                servers.add(serv);
            }
            if (servers.contains(server -> server.name.contains(args[0]))) {
                Server serv = servers.find(server -> server.name.contains(args[0]));
                Call.connect(parameter.con, serv.ip, Math.toIntExact(serv.port));
            }
        });
        handler.<Player>register("servers", "Lists all servers", (args, parameter) -> {
            JSONArray array = (JSONArray) servers.get("servers");
            StringBuilder list = new StringBuilder();
            list.append("[yellow]SERVER LIST:\n\n[white]");
            for (Object object : array) {
                JSONObject jsonObject = (JSONObject) object;
                list.append(jsonObject.get("servername")).append(": ").append(jsonObject.get("ip")).append(":").append(jsonObject.get("port")).append("\n");
            }
            parameter.sendMessage(list.toString());
        });
    }
}
