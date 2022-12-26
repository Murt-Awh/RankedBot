package com.kasp.rankedbot.instance;

import com.kasp.rankedbot.EmbedType;
import com.kasp.rankedbot.GameState;
import com.kasp.rankedbot.PickingMode;
import com.kasp.rankedbot.RankedBot;
import com.kasp.rankedbot.config.Config;
import com.kasp.rankedbot.instance.cache.GamesCache;
import com.kasp.rankedbot.instance.cache.MapsCache;
import com.kasp.rankedbot.instance.cache.PlayerCache;
import com.kasp.rankedbot.instance.cache.QueuesCache;
import com.kasp.rankedbot.instance.embed.Embed;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class Game {

    private int number;

    // discord
    private Guild guild;

    private Category channelsCategory;
    private Category vcsCategory;

    private TextChannel channel;
    private VoiceChannel vc1;
    private VoiceChannel vc2;

    private Queue queue;

    // game
    private List<Player> players;

    private Player captain1;
    private Player captain2;
    private Player currentCaptain;

    private List<Player> team1;
    private List<Player> team2;
    private List<Player> remainingPlayers;

    private GameState state;

    private boolean casual;
    private GameMap map;
    private Player mvp;
    private Member scoredBy; // this acts as voidedBy if game was voided

    public Game(List<Player> players, Queue queue) {
        guild = RankedBot.getGuild();
        this.players = players;
        this.queue = queue;

        RankedBot.serverStats.setGamesPlayed(RankedBot.serverStats.getGamesPlayed()+1);
        this.number = RankedBot.serverStats.getGamesPlayed();

        this.team1 = new ArrayList<>();
        this.team2 = new ArrayList<>();

        this.state = GameState.STARTING;

        this.casual = queue.isCasual();

        List<GameMap> maps = (List<GameMap>) MapsCache.getMaps().values();
        Collections.shuffle(maps);
        this.map = maps.get(0);

        this.channelsCategory = guild.getCategoryById(Config.getValue("game-channels-category"));
        this.vcsCategory = guild.getCategoryById(Config.getValue("game-vcs-category"));

        channel = channelsCategory.createTextChannel(Config.getValue("game-channel-names").replaceAll("%number%", number + "").replaceAll("%mode%", queue.getPlayersEachTeam() + "v" + queue.getPlayersEachTeam())).complete();
        vc1 = vcsCategory.createVoiceChannel(Config.getValue("game-vc-names").replaceAll("%number%", number + "").replaceAll("%mode%", queue.getPlayersEachTeam() + "v" +queue.getPlayersEachTeam()).replaceAll("%team%", "1")).setUserlimit(queue.getPlayersEachTeam()).complete();
        vc2 = vcsCategory.createVoiceChannel(Config.getValue("game-vc-names").replaceAll("%number%", number + "").replaceAll("%mode%", queue.getPlayersEachTeam() + "v" +queue.getPlayersEachTeam()).replaceAll("%team%", "2")).setUserlimit(queue.getPlayersEachTeam()).complete();

        Collections.shuffle(players);
        this.captain1 = players.get(0);
        this.captain2 = players.get(1);

        currentCaptain = captain1;
        if (captain1.getElo() > captain2.getElo()) {
            currentCaptain = captain2;
        }

        this.team1.add(captain1);
        this.team2.add(captain2);

        remainingPlayers = players;
        remainingPlayers.remove(captain1);
        remainingPlayers.remove(captain2);

        for (Player p : players) {
            channel.createPermissionOverride(guild.getMemberById(p.getID())).setAllow(Permission.VIEW_CHANNEL).queue();
            vc1.createPermissionOverride(guild.getMemberById(p.getID())).setAllow(Permission.VIEW_CHANNEL).setAllow(Permission.VOICE_CONNECT).queue();
            vc2.createPermissionOverride(guild.getMemberById(p.getID())).setAllow(Permission.VIEW_CHANNEL).setAllow(Permission.VOICE_CONNECT).queue();
        }

        for (Player p : remainingPlayers) {
            guild.moveVoiceMember(guild.getMemberById(p.getID()), vc1).queue();
        }

        GamesCache.initializeGame(number, this);
    }

    public Game(int number) {
        this.number = number;
        this.guild = RankedBot.getGuild();

        Yaml yaml = new Yaml();

        Map<String, Object> data;
        try {
            data = yaml.load(new FileInputStream("RankedBot/games/" + number + ".yml"));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }

        this.state = GameState.valueOf(data.get("state").toString().toUpperCase());
        this.casual = Boolean.parseBoolean(data.get("casual").toString());
        this.map = MapsCache.getMap(data.get("map").toString());
        this.channel = guild.getTextChannelById(data.get("channel-id").toString());
        this.vc1 = guild.getVoiceChannelById(data.get("vc1-id").toString());
        this.vc2 = guild.getVoiceChannelById(data.get("vc2-id").toString());
        this.queue = QueuesCache.getQueue(data.get("queue").toString());

        if (state != GameState.STARTING) {
            for (int i = 0; i < queue.getPlayers().size(); i++) {
                team1.add(PlayerCache.getPlayer(data.get("team1-" + i).toString()));
            }

            for (int i = 0; i < queue.getPlayers().size(); i++) {
                team2.add(PlayerCache.getPlayer(data.get("team2-" + i).toString()));
            }
        }

        if (state == GameState.SCORED) {
            this.mvp = PlayerCache.getPlayer(data.get("mvp").toString());
            this.scoredBy = guild.getMemberById(data.get("scored-by").toString());
        }

        if (state == GameState.VOIDED) {
            this.scoredBy = guild.getMemberById(data.get("scored-by").toString());
        }

        GamesCache.initializeGame(number, this);
    }

    public void pickTeams() {

        if (queue.getPickingMode() == PickingMode.AUTOMATIC) {

            for (int i = 0; i < queue.getPlayersEachTeam() * 2 - 2; i+=2) {
                this.team1.add(remainingPlayers.get(i));
                this.team2.add(remainingPlayers.get(i+1));
            }

            start();

        }
        else {

            sendGameMsg();

        }

    }

    public void start() {

        state = GameState.PLAYING;

        sendGameMsg();

        for (Player p : team2) {
            guild.moveVoiceMember(guild.getMemberById(p.getID()), vc2).queue();
        }


    }

    public void sendGameMsg() {
        String mentions = "";
        for (Player p : players) {
            mentions += guild.getMemberById(p.getID()).getAsMention();
        }

        Embed embed = new Embed(EmbedType.DEFAULT, "Game `#" + number + "`", "", 1);

        String team1 = "";
        for (Player p : this.team1) {
            team1 += "• <@!" + p.getID() + ">\n";
        }
        embed.addField("Team 1", team1, true);

        String team2 = "";
        for (Player p : this.team2) {
            team2 += "• <@!" + p.getID() + ">\n";
        }
        embed.addField("Team 2", team2, true);

        if (remainingPlayers.size() != 0) {
            String remaining = "";
            for (Player p : remainingPlayers) {
                remaining += "• <@!" + p.getID() + ">\n";
            }

            embed.addField("Remaining", remaining, false);
        }

        embed.addField("Randomly Picked Map", "**" + map + "** — `Height: " + map.getHeight() + "` (" + map.getTeam1() + " vs " + map.getTeam2() + ")", false);

        if (remainingPlayers.size() == 0) {
            embed.setTitle("Game `#" + number + "` has started!");
            if (casual) {
                embed.setDescription("You queued a casual queue meaning this game will have no impact on players' stats");
            }

            embed.setDescription("do not forget to =submit after your game ends");
        }

        channel.sendMessage(mentions).setEmbeds(embed.build()).queue();
    }

    // bool - was the action successful or not
    public boolean pickPlayer(Player sender, Player picked) {
        if (state != GameState.STARTING) {
            return false;
        }

        if (sender != currentCaptain) {
            return false;
        }

        if (!remainingPlayers.contains(picked)) {
            return false;
        }

        if (sender == picked) {
            return false;
        }

        getPlayerTeam(sender).add(picked);
        remainingPlayers.remove(picked);

        sendGameMsg();

        if (remainingPlayers.size() == 1) {
            getOppTeam(sender).add(remainingPlayers.get(0));
            remainingPlayers.remove(remainingPlayers.get(0));

            start();
        }

        return true;
    }

    public List<Player> getPlayerTeam(Player player) {
        if (team1.contains(player)) {
            return team1;
        }

        if (team2.contains(player)) {
            return team2;
        }

        return null;
    }

    public List<Player> getOppTeam(Player player) {
        if (!team1.contains(player)) {
            return team1;
        }

        if (!team2.contains(player)) {
            return team2;
        }

        return null;
    }

    public static void writeFile(Game g) {
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter("RBW/games/" + g.getNumber() + ".yml"));

            bw.write("state: " + g.getState() + "\n");
            bw.write("casual: " + g.isCasual() + "\n");
            bw.write("map: " + g.getMap() + "\n");
            bw.write("channel-id: " + g.getChannel().getId() + "\n");
            bw.write("vc1-id: " + g.getVc1().getId() + "\n");
            bw.write("vc2-id: " + g.getVc2().getId() + "\n");
            bw.write("queue-id: " + g.getQueue().getID() + "\n");

            if (g.getState() != GameState.STARTING) {
                for (int i = 0; i < g.getTeam1().size(); i++) {
                    bw.write("team1-" + i + ": " + g.getTeam1().get(i).getID() + "\n");
                }
                for (int i = 0; i < g.getTeam2().size(); i++) {
                    bw.write("team2-" + i + ": " + g.getTeam2().get(i).getID() + "\n");
                }
            }

            if (g.getState() == GameState.SCORED) {
                bw.write("mvp: " + g.getMvp().getID());
                bw.write("scored-by: " + g.getScoredBy().getId());
            }

            if (g.getState() == GameState.VOIDED) {
                bw.write("scored-by: " + g.getScoredBy().getId());
            }

            bw.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public int getNumber() {
        return number;
    }

    public TextChannel getChannel() {
        return channel;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public Guild getGuild() {
        return guild;
    }

    public void setGuild(Guild guild) {
        this.guild = guild;
    }

    public Category getChannelsCategory() {
        return channelsCategory;
    }

    public void setChannelsCategory(Category channelsCategory) {
        this.channelsCategory = channelsCategory;
    }

    public Category getVcsCategory() {
        return vcsCategory;
    }

    public void setVcsCategory(Category vcsCategory) {
        this.vcsCategory = vcsCategory;
    }

    public void setChannel(TextChannel channel) {
        this.channel = channel;
    }

    public VoiceChannel getVc1() {
        return vc1;
    }

    public void setVc1(VoiceChannel vc1) {
        this.vc1 = vc1;
    }

    public VoiceChannel getVc2() {
        return vc2;
    }

    public void setVc2(VoiceChannel vc2) {
        this.vc2 = vc2;
    }

    public Queue getQueue() {
        return queue;
    }

    public void setQueue(Queue queue) {
        this.queue = queue;
    }

    public List<Player> getPlayers() {
        return players;
    }

    public void setPlayers(List<Player> players) {
        this.players = players;
    }

    public Player getCaptain1() {
        return captain1;
    }

    public void setCaptain1(Player captain1) {
        this.captain1 = captain1;
    }

    public Player getCaptain2() {
        return captain2;
    }

    public void setCaptain2(Player captain2) {
        this.captain2 = captain2;
    }

    public List<Player> getTeam1() {
        return team1;
    }

    public void setTeam1(List<Player> team1) {
        this.team1 = team1;
    }

    public List<Player> getTeam2() {
        return team2;
    }

    public void setTeam2(List<Player> team2) {
        this.team2 = team2;
    }

    public List<Player> getRemainingPlayers() {
        return remainingPlayers;
    }

    public void setRemainingPlayers(List<Player> remainingPlayers) {
        this.remainingPlayers = remainingPlayers;
    }

    public GameState getState() {
        return state;
    }

    public void setState(GameState state) {
        this.state = state;
    }

    public boolean isCasual() {
        return casual;
    }

    public void setCasual(boolean casual) {
        this.casual = casual;
    }

    public GameMap getMap() {
        return map;
    }

    public void setMap(GameMap map) {
        this.map = map;
    }

    public Player getMvp() {
        return mvp;
    }

    public void setMvp(Player mvp) {
        this.mvp = mvp;
    }

    public Member getScoredBy() {
        return scoredBy;
    }

    public void setScoredBy(Member scoredBy) {
        this.scoredBy = scoredBy;
    }
}
