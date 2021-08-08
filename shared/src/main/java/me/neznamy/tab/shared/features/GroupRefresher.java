package me.neznamy.tab.shared.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import me.neznamy.tab.api.TabFeature;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.shared.ITabPlayer;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.cpu.UsageType;

/**
 * Permission group refresher
 */
public class GroupRefresher extends TabFeature {

	public static final String DEFAULT_GROUP = "NONE";
	
	private boolean groupsByPermissions;
	private List<String> primaryGroupFindingList;
	
	public GroupRefresher() {
		super("Permission group refreshing");
		groupsByPermissions = TAB.getInstance().getConfiguration().getConfig().getBoolean("assign-groups-by-permissions", false);
		primaryGroupFindingList = new ArrayList<>();
		for (Object group : TAB.getInstance().getConfiguration().getConfig().getStringList("primary-group-finding-list", Arrays.asList("Owner", "Admin", "Helper", "default"))){
			primaryGroupFindingList.add(group.toString());
		}
		TAB.getInstance().getCPUManager().startRepeatingMeasuredTask(1000, "refreshing permission groups", this, UsageType.REPEATING_TASK, () -> {

			for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) {
				((ITabPlayer) p).setGroup(detectPermissionGroup(p), true); 
			}
		});
	}

	public String detectPermissionGroup(TabPlayer p) {
		if (isGroupsByPermissions()) {
			return getByPermission(p);
		}
		return getByPrimary(p);
	}

	public String getByPrimary(TabPlayer p) {
		try {
			return TAB.getInstance().getPermissionPlugin().getPrimaryGroup(p);
		} catch (Exception e) {
			TAB.getInstance().getErrorManager().printError("Failed to get permission group of " + p.getName() + " using " + TAB.getInstance().getPermissionPlugin().getName() + " v" + TAB.getInstance().getPermissionPlugin().getVersion(), e);
			return DEFAULT_GROUP;
		}
	}

	public String getByPermission(TabPlayer p) {
		for (Object group : primaryGroupFindingList) {
			if (p.hasPermission("tab.group." + group)) {
				return String.valueOf(group);
			}
		}
		return DEFAULT_GROUP;
	}

	public boolean isGroupsByPermissions() {
		return groupsByPermissions;
	}
}