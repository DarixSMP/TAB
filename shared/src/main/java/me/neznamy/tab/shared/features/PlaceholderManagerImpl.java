package me.neznamy.tab.shared.features;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import me.neznamy.tab.api.TabFeature;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.placeholder.Placeholder;
import me.neznamy.tab.api.placeholder.PlaceholderManager;
import me.neznamy.tab.api.placeholder.PlayerPlaceholder;
import me.neznamy.tab.api.placeholder.RelationalPlaceholder;
import me.neznamy.tab.api.placeholder.ServerPlaceholder;
import me.neznamy.tab.api.task.RepeatingTask;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.placeholders.PlayerPlaceholderImpl;
import me.neznamy.tab.shared.placeholders.RelationalPlaceholderImpl;
import me.neznamy.tab.shared.placeholders.ServerPlaceholderImpl;
import me.neznamy.tab.shared.placeholders.TabPlaceholder;

/**
 * Messy class for placeholder management
 */
public class PlaceholderManagerImpl extends TabFeature implements PlaceholderManager {

	private final Pattern placeholderPattern = Pattern.compile("%([^%]*)%");

	private int defaultRefresh = TAB.getInstance().getConfiguration().getConfig().getInt("placeholderapi-refresh-intervals.default-refresh-interval", 100);
	private final Map<String, Integer> serverPlaceholderRefreshIntervals = new HashMap<>();
	private final Map<String, Integer> playerPlaceholderRefreshIntervals = new HashMap<>();
	private final Map<String, Integer> relationalPlaceholderRefreshIntervals = new HashMap<>();

	//plugin internals + PAPI + API
	private final Map<String, Placeholder> registeredPlaceholders = new HashMap<>();

	//map of String-Set of features using placeholder
	private final Map<String, Set<TabFeature>> placeholderUsage = new ConcurrentHashMap<>();
	private Placeholder[] usedPlaceholders = new Placeholder[0];
	
	private final AtomicInteger atomic = new AtomicInteger();
	private final RepeatingTask refreshTask;

	public PlaceholderManagerImpl(){
		super("Refreshing placeholders", "Updating placeholders");
		loadRefreshIntervals();
		refreshTask = TAB.getInstance().getCPUManager().startRepeatingMeasuredTask(10000, "refreshing placeholders", this, "Refreshing placeholders", this::refresh);
	}
	
	private void refresh() {
		int loopTime = atomic.addAndGet(refreshTask.getInterval());
		int size = TAB.getInstance().getOnlinePlayers().length;
		Map<TabPlayer, Set<TabFeature>> update = new HashMap<>(size);
		Map<TabPlayer, Set<TabFeature>> forceUpdate = new HashMap<>(size);
		boolean somethingChanged = false;
		for (Placeholder placeholder : usedPlaceholders) {
			if (loopTime % placeholder.getRefresh() != 0) continue;
			if (placeholder instanceof RelationalPlaceholderImpl && updateRelationalPlaceholder((RelationalPlaceholderImpl) placeholder, forceUpdate)) somethingChanged = true;
			if (placeholder instanceof PlayerPlaceholderImpl && updatePlayerPlaceholder((PlayerPlaceholderImpl) placeholder, update)) somethingChanged = true;
			if (placeholder instanceof ServerPlaceholderImpl && updateServerPlaceholder((ServerPlaceholderImpl) placeholder, update)) somethingChanged = true;
		}
		if (somethingChanged) refresh(forceUpdate, update);
	}
	
	private void refresh(Map<TabPlayer, Set<TabFeature>> forceUpdate, Map<TabPlayer, Set<TabFeature>> update) {
		for (Entry<TabPlayer, Set<TabFeature>> entry : update.entrySet()) {
			if (forceUpdate.containsKey(entry.getKey())) {
				entry.getValue().removeAll(forceUpdate.get(entry.getKey()));
			}
		}
		long startRefreshTime = System.nanoTime();
		for (Entry<TabPlayer, Set<TabFeature>> entry : forceUpdate.entrySet()) {
			for (TabFeature r : entry.getValue()) {
				long startTime = System.nanoTime();
				r.refresh(entry.getKey(), true);
				TAB.getInstance().getCPUManager().addTime(r.getFeatureName(), r.getRefreshDisplayName(), System.nanoTime()-startTime);
			}
		}
		for (Entry<TabPlayer, Set<TabFeature>> entry : update.entrySet()) {
			for (TabFeature r : entry.getValue()) {
				long startTime = System.nanoTime();
				r.refresh(entry.getKey(), false);
				TAB.getInstance().getCPUManager().addTime(r.getFeatureName(), r.getRefreshDisplayName(), System.nanoTime()-startTime);
			}
		}
		//subtracting back usage by this method from placeholder refreshing usage, since it is already counted under different name in this method
		TAB.getInstance().getCPUManager().addTime(getFeatureName(), TabConstants.CpuUsageCategory.PLACEHOLDER_REFRESHING, startRefreshTime-System.nanoTime());
	}

	private boolean updateRelationalPlaceholder(RelationalPlaceholderImpl placeholder, Map<TabPlayer, Set<TabFeature>> forceUpdate) {
		boolean somethingChanged = false;
		long startTime = System.nanoTime();
		for (TabPlayer p1 : TAB.getInstance().getOnlinePlayers()) {
			if (!p1.isLoaded()) continue;
			for (TabPlayer p2 : TAB.getInstance().getOnlinePlayers()) {
				if (!p2.isLoaded()) continue;
				if (placeholder.update(p1, p2)) {
					forceUpdate.computeIfAbsent(p2, x -> new HashSet<>()).addAll(placeholderUsage.get(placeholder.getIdentifier()));
					somethingChanged = true;
				}
				if (placeholder.update(p2, p1)) {
					forceUpdate.computeIfAbsent(p1, x -> new HashSet<>()).addAll(placeholderUsage.get(placeholder.getIdentifier()));
					somethingChanged = true;
				}
			}
		}
		TAB.getInstance().getCPUManager().addPlaceholderTime(placeholder.getIdentifier(), System.nanoTime()-startTime);
		return somethingChanged;
	}

	private boolean updatePlayerPlaceholder(PlayerPlaceholderImpl placeholder, Map<TabPlayer, Set<TabFeature>> update) {
		boolean somethingChanged = false;
		long startTime = System.nanoTime();
		for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
			if (all.isLoaded() && placeholder.update(all)) {
				update.computeIfAbsent(all, k -> new HashSet<>()).addAll(placeholderUsage.get(placeholder.getIdentifier()));
				somethingChanged = true;
			}
		}
		TAB.getInstance().getCPUManager().addPlaceholderTime(placeholder.getIdentifier(), System.nanoTime()-startTime);
		return somethingChanged;
	}

	private boolean updateServerPlaceholder(ServerPlaceholderImpl placeholder, Map<TabPlayer, Set<TabFeature>> update) {
		boolean somethingChanged = false;
		long startTime = System.nanoTime();
		if (placeholder.update()) {
			somethingChanged = true;
			for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
				if (!all.isLoaded()) continue;
				update.computeIfAbsent(all, k -> new HashSet<>()).addAll(placeholderUsage.get(placeholder.getIdentifier()));
			}
		}
		TAB.getInstance().getCPUManager().addPlaceholderTime(placeholder.getIdentifier(), System.nanoTime()-startTime);
		return somethingChanged;
	}

	private void loadRefreshIntervals() {
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

	public int getRelationalRefresh(String identifier) {
		return relationalPlaceholderRefreshIntervals.getOrDefault(identifier, defaultRefresh);
	}

	public Collection<Placeholder> getAllPlaceholders(){
		return new ArrayList<>(registeredPlaceholders.values());
	}

	public Placeholder registerPlaceholder(Placeholder placeholder) {
		if (placeholder == null) throw new IllegalArgumentException("Placeholder cannot be null");
		registeredPlaceholders.put(placeholder.getIdentifier(), placeholder);
		usedPlaceholders = placeholderUsage.keySet().stream().map(this::getPlaceholder).filter(p -> !p.isTriggerMode()).collect(Collectors.toSet()).toArray(new Placeholder[0]);
		return placeholder;
	}
	
	public Placeholder[] getUsedPlaceholders() {
		return usedPlaceholders;
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
	
	@Override
	public void load() {
		for (Placeholder pl : usedPlaceholders) {
			if (pl instanceof ServerPlaceholderImpl) {
				((ServerPlaceholderImpl)pl).update();
			}
		}
		for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) {
			onJoin(p);
		}
	}
	
	@Override
	public void unload() {
		registeredPlaceholders.values().forEach(p -> p.unload());
	}

	@Override
	public void onJoin(TabPlayer connectedPlayer) {
		for (Placeholder pl : usedPlaceholders) {
			if (pl instanceof RelationalPlaceholderImpl) {
				for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
					((RelationalPlaceholderImpl)pl).update(connectedPlayer, all);
					((RelationalPlaceholderImpl)pl).update(all, connectedPlayer);
				}
			}
			if (pl instanceof PlayerPlaceholderImpl) {
				((PlayerPlaceholderImpl)pl).update(connectedPlayer);
			}
		}
	}

	@Override
	public void onQuit(TabPlayer disconnectedPlayer) {
		for (Placeholder pl : usedPlaceholders) {
			if (pl instanceof RelationalPlaceholderImpl) {
				for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
					((RelationalPlaceholderImpl)pl).getLastValues().remove(all.getName() + "-" + disconnectedPlayer.getName());
					((RelationalPlaceholderImpl)pl).getLastValues().remove(disconnectedPlayer.getName() + "-" + all.getName());
				}
			}
			if (pl instanceof PlayerPlaceholderImpl) {
				((PlayerPlaceholderImpl)pl).getLastValues().remove(disconnectedPlayer.getName());
				((PlayerPlaceholderImpl)pl).getForceUpdate().remove(disconnectedPlayer.getName());
			}
		}
	}

	@Override
	public ServerPlaceholder registerServerPlaceholder(String identifier, int refresh, Supplier<Object> supplier) {
		return (ServerPlaceholder) registerPlaceholder(new ServerPlaceholderImpl(identifier, refresh, supplier));
	}
	
	@Override
	public PlayerPlaceholder registerPlayerPlaceholder(String identifier, int refresh, Function<TabPlayer, Object> function) {
		return (PlayerPlaceholder) registerPlaceholder(new PlayerPlaceholderImpl(identifier, refresh, function));
	}

	@Override
	public RelationalPlaceholder registerRelationalPlaceholder(String identifier, int refresh, BiFunction<TabPlayer, TabPlayer, Object> function) {
		return (RelationalPlaceholder) registerPlaceholder(new RelationalPlaceholderImpl(identifier, refresh, function));
	}

	@Override
	public List<String> detectPlaceholders(String text){
		List<String> placeholders = new ArrayList<>();
		if (text == null || !text.contains("%")) return placeholders;
		Matcher m = placeholderPattern.matcher(text);
		while (m.find()) {
			placeholders.add(m.group());
		}
		return placeholders;
	}

	public TabPlaceholder getPlaceholder(String identifier) {
		TabPlaceholder p = (TabPlaceholder) registeredPlaceholders.get(identifier);
		if (p == null) {
			TAB.getInstance().getPlatform().registerUnknownPlaceholder(identifier);
			addUsedPlaceholder(identifier, this); //likely used via tab expansion
			return getPlaceholder(identifier);
		}
		if (!placeholderUsage.containsKey(identifier)) {
			//tab expansion for internal placeholder
			addUsedPlaceholder(identifier, this);
		}
		return p;
	}

	@Override
	public void addUsedPlaceholder(String identifier, TabFeature feature) {
		if (placeholderUsage.computeIfAbsent(identifier, x -> new HashSet<>()).add(feature)) {
			usedPlaceholders = placeholderUsage.keySet().stream().map(this::getPlaceholder).filter(p -> !p.isTriggerMode()).collect(Collectors.toSet()).toArray(new Placeholder[0]);
			TabPlaceholder p = getPlaceholder(identifier);
			p.markAsUsed();
			if (p.getRefresh() % 50 == 0 && p.getRefresh() > 0 && refreshTask.getInterval() > p.getRefresh() && !p.isTriggerMode()) {
				TAB.getInstance().debug("Decreasing refresh interval of placeholder refreshing task to " + p.getRefresh() + "ms due to placeholder " + identifier);
				refreshTask.setInterval(p.getRefresh());
				atomic.set(0);
			} 
		}
	}

	@Override
	public String findReplacement(String placeholder, String output) {
		return getPlaceholder(placeholder).getReplacements().findReplacement(output);
	}
	
	public Map<String, Set<TabFeature>> getPlaceholderUsage(){
		return placeholderUsage;
	}
}