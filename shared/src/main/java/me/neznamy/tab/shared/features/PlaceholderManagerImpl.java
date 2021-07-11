package me.neznamy.tab.shared.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Preconditions;

import me.neznamy.tab.api.PlaceholderManager;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.cpu.UsageType;
import me.neznamy.tab.shared.placeholders.Placeholder;
import me.neznamy.tab.shared.placeholders.PlaceholderRegistry;
import me.neznamy.tab.shared.placeholders.PlayerPlaceholder;
import me.neznamy.tab.shared.placeholders.RelationalPlaceholder;
import me.neznamy.tab.shared.placeholders.ServerPlaceholder;

/**
 * Messy class for placeholder management
 */
public class PlaceholderManagerImpl extends TabFeature implements PlaceholderManager {

	private final Pattern placeholderPattern = Pattern.compile("%([^%]*)%");
	
	private int defaultRefresh;
	private Map<String, Integer> serverPlaceholderRefreshIntervals = new HashMap<>();
	private Map<String, Integer> playerPlaceholderRefreshIntervals = new HashMap<>();
	private Map<String, Integer> relationalPlaceholderRefreshIntervals = new HashMap<>();
	
	//all placeholders used in all configuration files + API, including invalid ones
	private Set<String> allUsedPlaceholderIdentifiers = new HashSet<>();
	
	//plugin internals + PAPI + API
	private Map<String, Placeholder> registeredPlaceholders = new HashMap<>();

	private List<PlaceholderRegistry> registry = new ArrayList<>();
	
	//map of String-Set of features using placeholder
	private Map<String, Set<TabFeature>> placeholderUsage;

	public PlaceholderManagerImpl(){
		findAllUsed(TAB.getInstance().getConfiguration().getConfig().getValues());
		findAllUsed(TAB.getInstance().getConfiguration().getAnimationFile().getValues());
		findAllUsed(TAB.getInstance().getConfiguration().getBossbarConfig().getValues());
		if (TAB.getInstance().getConfiguration().getPremiumConfig() != null) findAllUsed(TAB.getInstance().getConfiguration().getPremiumConfig().getValues());
		loadRefreshIntervals();
		AtomicInteger atomic = new AtomicInteger();
		TAB.getInstance().getCPUManager().startRepeatingMeasuredTask(50, "refreshing placeholders", getFeatureType(), UsageType.REPEATING_TASK, () -> {

			int loopTime = atomic.addAndGet(50);
			if (placeholderUsage == null) return; //plugin still starting up
			Map<TabPlayer, Set<TabFeature>> update = null; //not initializing if not needed
			Map<TabPlayer, Set<TabFeature>> forceUpdate = null;
			boolean somethingChanged = false;
			for (String identifier : getAllUsedPlaceholderIdentifiers()) {
				Placeholder placeholder = getPlaceholder(identifier);
				if (loopTime % placeholder.getRefresh() != 0) continue;
				if (placeholder instanceof RelationalPlaceholder) {
					if (forceUpdate == null) forceUpdate = new HashMap<>();
					if (updateRelationalPlaceholder((RelationalPlaceholder) placeholder, forceUpdate)) somethingChanged = true;
				}
				if (update == null) {
					update = new HashMap<>();
				}
				if (placeholder instanceof PlayerPlaceholder && updatePlayerPlaceholder((PlayerPlaceholder) placeholder, update)) somethingChanged = true;
				if (placeholder instanceof ServerPlaceholder && updateServerPlaceholder((ServerPlaceholder) placeholder, update)) somethingChanged = true;
			}
			if (somethingChanged) refresh(forceUpdate, update);
		});
	}
	
	private boolean updateRelationalPlaceholder(RelationalPlaceholder placeholder, Map<TabPlayer, Set<TabFeature>> forceUpdate) {
		boolean somethingChanged = false;
		long startTime = System.nanoTime();
		for (TabPlayer p1 : TAB.getInstance().getPlayers()) {
			if (!p1.isLoaded()) continue;
			for (TabPlayer p2 : TAB.getInstance().getPlayers()) {
				if (!p2.isLoaded()) continue;
				if (placeholder.update(p1, p2)) {
					if (!forceUpdate.containsKey(p2)) forceUpdate.put(p2, new HashSet<>());
					forceUpdate.get(p2).addAll(placeholderUsage.get(placeholder.getIdentifier()));
					somethingChanged = true;
				}
				if (placeholder.update(p2, p1)) {
					if (!forceUpdate.containsKey(p1)) forceUpdate.put(p1, new HashSet<>());
					forceUpdate.get(p1).addAll(placeholderUsage.get(placeholder.getIdentifier()));
					somethingChanged = true;
				}
			}
		}
		TAB.getInstance().getCPUManager().addPlaceholderTime(placeholder.getIdentifier(), System.nanoTime()-startTime);
		return somethingChanged;
	}
	
	private boolean updatePlayerPlaceholder(PlayerPlaceholder placeholder, Map<TabPlayer, Set<TabFeature>> update) {
		boolean somethingChanged = false;
		long startTime = System.nanoTime();
		for (TabPlayer all : TAB.getInstance().getPlayers()) {
			if (!all.isLoaded()) continue;
			if (placeholder.update(all)) {
				update.computeIfAbsent(all, k -> new HashSet<>());
				update.get(all).addAll(placeholderUsage.get(placeholder.getIdentifier()));
				somethingChanged = true;
			}
		}
		TAB.getInstance().getCPUManager().addPlaceholderTime(placeholder.getIdentifier(), System.nanoTime()-startTime);
		return somethingChanged;
	}
	
	private boolean updateServerPlaceholder(ServerPlaceholder placeholder, Map<TabPlayer, Set<TabFeature>> update) {
		boolean somethingChanged = false;
		long startTime = System.nanoTime();
		if (placeholder.update()) {
			somethingChanged = true;
			for (TabPlayer all : TAB.getInstance().getPlayers()) {
				if (!all.isLoaded()) continue;
				update.computeIfAbsent(all, k -> new HashSet<>());
				update.get(all).addAll(placeholderUsage.get(placeholder.getIdentifier()));
			}
		}
		TAB.getInstance().getCPUManager().addPlaceholderTime(placeholder.getIdentifier(), System.nanoTime()-startTime);
		return somethingChanged;
	}
	
	private void loadRefreshIntervals() {
		for (Object category : TAB.getInstance().getConfiguration().getConfig().getConfigurationSection("placeholderapi-refresh-intervals").keySet()) {
			if (!Arrays.asList("default-refresh-interval", "server", "player", "relational").contains(category.toString())) {
				TAB.getInstance().getErrorManager().startupWarn("Unknown placeholder category \"" + category + "\". Valid categories are \"server\", \"player\" and \"relational\"");
			}
		}
		defaultRefresh = TAB.getInstance().getConfiguration().getConfig().getInt("placeholderapi-refresh-intervals.default-refresh-interval", 100);
		for (Entry<Object, Object> placeholder : TAB.getInstance().getConfiguration().getConfig().getConfigurationSection("placeholderapi-refresh-intervals.server").entrySet()) {
			serverPlaceholderRefreshIntervals.put(placeholder.getKey().toString(), TAB.getInstance().getErrorManager().parseInteger(placeholder.getValue().toString(), getDefaultRefresh(), "refresh interval of server placeholder"));
		}
		for (Entry<Object, Object> placeholder : TAB.getInstance().getConfiguration().getConfig().getConfigurationSection("placeholderapi-refresh-intervals.player").entrySet()) {
			playerPlaceholderRefreshIntervals.put(placeholder.getKey().toString(), TAB.getInstance().getErrorManager().parseInteger(placeholder.getValue().toString(), getDefaultRefresh(), "refresh interval of player placeholder"));
		}
		for (Entry<Object, Object> placeholder : TAB.getInstance().getConfiguration().getConfig().getConfigurationSection("placeholderapi-refresh-intervals.relational").entrySet()) {
			relationalPlaceholderRefreshIntervals.put(placeholder.getKey().toString(), TAB.getInstance().getErrorManager().parseInteger(placeholder.getValue().toString(), getDefaultRefresh(), "refresh interval of relational placeholder"));
		}
	}
	
	private void refresh(Map<TabPlayer, Set<TabFeature>> forceUpdate, Map<TabPlayer, Set<TabFeature>> update) {
		if (forceUpdate != null) 
			for (Entry<TabPlayer, Set<TabFeature>> entry : update.entrySet()) {
				if (forceUpdate.containsKey(entry.getKey())) {
					entry.getValue().removeAll(forceUpdate.get(entry.getKey()));
				}
			}
		long startRefreshTime = System.nanoTime();
		if (forceUpdate != null) 
			for (Entry<TabPlayer, Set<TabFeature>> entry : forceUpdate.entrySet()) {
				for (TabFeature r : entry.getValue()) {
					long startTime = System.nanoTime();
					r.refresh(entry.getKey(), true);
					TAB.getInstance().getCPUManager().addTime(r.getFeatureType(), UsageType.REFRESHING, System.nanoTime()-startTime);
				}
			}
		for (Entry<TabPlayer, Set<TabFeature>> entry : update.entrySet()) {
			for (TabFeature r : entry.getValue()) {
				long startTime = System.nanoTime();
				r.refresh(entry.getKey(), false);
				TAB.getInstance().getCPUManager().addTime(r.getFeatureType(), UsageType.REFRESHING, System.nanoTime()-startTime);
			}
		}
		//subtracting back usage by this method from placeholder refreshing usage, since it is already counted under different name in this method
		TAB.getInstance().getCPUManager().addTime(getFeatureType(), UsageType.REPEATING_TASK, startRefreshTime-System.nanoTime());
	}

	public void refreshPlaceholderUsage() {
		for (TabFeature r : TAB.getInstance().getFeatureManager().getAllFeatures()) {
			r.refreshUsedPlaceholders();
		}
		Map<String, Set<TabFeature>> placeholderUsage0 = new HashMap<>();
		for (String placeholder : getAllUsedPlaceholderIdentifiers()) {
			Set<TabFeature> set = new HashSet<>();
			for (TabFeature r : TAB.getInstance().getFeatureManager().getAllFeatures()) {
				if (r.getUsedPlaceholders().contains(placeholder)) set.add(r);
			}
			placeholderUsage0.put(placeholder, set);
		}
		placeholderUsage = placeholderUsage0;
	}

	@Override
	public void load() {
		refreshPlaceholderUsage();
		for (TabPlayer p : TAB.getInstance().getPlayers()) {
			onJoin(p);
		}
	}

	@Override
	public void onJoin(TabPlayer connectedPlayer) {
		for (String identifier : getAllUsedPlaceholderIdentifiers()) {
			Placeholder pl = getPlaceholder(identifier);
			if (pl instanceof RelationalPlaceholder) {
				for (TabPlayer all : TAB.getInstance().getPlayers()) {
					((RelationalPlaceholder)pl).update(connectedPlayer, all);
					((RelationalPlaceholder)pl).update(all, connectedPlayer);
				}
			}
			if (pl instanceof PlayerPlaceholder) {
				((PlayerPlaceholder)pl).update(connectedPlayer);
			}
		}
	}
	
	@Override
	public void onQuit(TabPlayer disconnectedPlayer) {
		for (String identifier : getAllUsedPlaceholderIdentifiers()) {
			Placeholder pl = getPlaceholder(identifier);
			if (pl instanceof RelationalPlaceholder) {
				for (TabPlayer all : TAB.getInstance().getPlayers()) {
					((RelationalPlaceholder)pl).getLastValues().remove(all.getName() + "-" + disconnectedPlayer.getName());
					((RelationalPlaceholder)pl).getLastValues().remove(disconnectedPlayer.getName() + "-" + all.getName());
				}
			}
			if (pl instanceof PlayerPlaceholder) {
				((PlayerPlaceholder)pl).getLastValues().remove(disconnectedPlayer.getName());
				((PlayerPlaceholder)pl).getForceUpdate().remove(disconnectedPlayer.getName());
			}
		}
	}

	public void addRegistry(PlaceholderRegistry registry) {
		this.registry.add(registry);
	}
	
	public void registerPlaceholders() {
		for (PlaceholderRegistry r : registry) {
			for (Placeholder p : r.registerPlaceholders()) {
				registerPlaceholder(p);
			}
		}
	}
	
	public int getRelationalRefresh(String identifier) {
		if (relationalPlaceholderRefreshIntervals.containsKey(identifier)) {
			return relationalPlaceholderRefreshIntervals.get(identifier);
		} else {
			return defaultRefresh;
		}
	}

	public Collection<Placeholder> getAllPlaceholders(){
		return new ArrayList<>(registeredPlaceholders.values());
	}
	
	public Placeholder getPlaceholder(String identifier) {
		Placeholder p = registeredPlaceholders.get(identifier);
		if (p == null) {
			p = TAB.getInstance().getPlatform().registerUnknownPlaceholder(identifier);
		}
		return p;
	}
	
	public List<String> getUsedPlaceholderIdentifiersRecursive(String... strings){
		List<String> base = new ArrayList<>();
		for (String string : strings) {
			for (String s : detectAll(color(string))) {
				if (!base.contains(s)) base.add(s);
			}
		}
		for (String placeholder : new HashSet<>(base)) {
			Placeholder pl = TAB.getInstance().getPlaceholderManager().getPlaceholder(placeholder);
			if (pl == null) continue;
			for (String nestedString : pl.getNestedStrings()) {
				base.addAll(getUsedPlaceholderIdentifiersRecursive(color(nestedString)));
			}
		}
		return base;
	}
	
	public List<String> detectAll(String text){
		List<String> placeholders = new ArrayList<>();
		if (text == null) return placeholders;
		Matcher m = placeholderPattern.matcher(text);
		while (m.find()) {
			placeholders.add(m.group());
		}
		return placeholders;
	}
	
	@SuppressWarnings("unchecked")
	public void findAllUsed(Object object) {
		if (object instanceof Map) {
			for (Object value : ((Map<String, Object>) object).values()) {
				findAllUsed(value);
			}
		}
		if (object instanceof List) {
			for (Object line : (List<Object>)object) {
				findAllUsed(line);
			}
		}
		if (object instanceof String) {
			for (String placeholder : detectAll((String) object)) {
				getAllUsedPlaceholderIdentifiers().add(color(placeholder));
			}
		}
	}

	public void registerPlaceholder(Placeholder placeholder) {
		Preconditions.checkNotNull(placeholder, "placeholder");
		registeredPlaceholders.put(placeholder.getIdentifier(), placeholder);
	}
	
	//code taken from bukkit, so it can work on bungee too
	public String color(String textToTranslate){
		if (textToTranslate == null) return null;
		if (!textToTranslate.contains("&")) return textToTranslate;
		char[] b = textToTranslate.toCharArray();
		for (int i = 0; i < b.length - 1; i++) {
			if ((b[i] == '&') && ("0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(b[(i + 1)]) > -1)){
				b[i] = '\u00a7';
				b[(i + 1)] = Character.toLowerCase(b[(i + 1)]);
			}
		}
		return new String(b);
	}
	
	//code taken from bukkit, so it can work on bungee too
	public String getLastColors(String input) {
		String result = "";
		int length = input.length();
		for (int index = length - 1; index > -1; index--){
			char section = input.charAt(index);
			if ((section == '\u00a7' || section == '&') && (index < length - 1)){
				char c = input.charAt(index + 1);
				if ("0123456789AaBbCcDdEeFfKkLlMmNnOoRr".contains(String.valueOf(c))) {
					result = "\u00a7" + c + result;
					if ("0123456789AaBbCcDdEeFfRr".contains(String.valueOf(c))) {
						break;
					}
				}
			}
		}
		return result;
	}
	

	/**
	 * Registers a player placeholder (placeholder with player-specific output)
	 * @param placeholder - Placeholder handler
	 * @see registerServerPlaceholder
	 * @see registerServerConstant
	 */
	@Override
	public void registerPlayerPlaceholder(PlayerPlaceholder placeholder) {
		registerPlaceholder(placeholder);
		getAllUsedPlaceholderIdentifiers().add(placeholder.getIdentifier());
		refreshPlaceholderUsage();
	}

	/**
	 * Registers a server placeholder (placeholder with same output for all players)
	 * @param placeholder - Placeholder handler
	 * @see registerPlayerPlaceholder
	 * @see registerServerConstant
	 */
	@Override
	public void registerServerPlaceholder(ServerPlaceholder placeholder) {
		registerPlaceholder(placeholder);
		getAllUsedPlaceholderIdentifiers().add(placeholder.getIdentifier());
		refreshPlaceholderUsage();
	}

	/**
	 * Registers a relational placeholder (different output for each player pair)
	 * @param placeholder - Placeholder handler
	 */
	@Override
	public void registerRelationalPlaceholder(RelationalPlaceholder placeholder) {
		registerPlaceholder(placeholder);
		getAllUsedPlaceholderIdentifiers().add(placeholder.getIdentifier());
		refreshPlaceholderUsage();
	}

	@Override
	public String getFeatureType() {
		return "Refreshing placeholders";
	}

	public Set<String> getAllUsedPlaceholderIdentifiers() {
		return allUsedPlaceholderIdentifiers;
	}

	public Map<String, Integer> getServerPlaceholderRefreshIntervals() {
		return serverPlaceholderRefreshIntervals;
	}

	public Map<String, Integer> getPlayerPlaceholderRefreshIntervals() {
		return playerPlaceholderRefreshIntervals;
	}

	public int getDefaultRefresh() {
		return defaultRefresh;
	}
}