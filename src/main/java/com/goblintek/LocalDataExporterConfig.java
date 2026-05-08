package com.goblintek;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("localdataexporter")
public interface LocalDataExporterConfig extends Config
{
	@ConfigItem(
			keyName = "exportDirectory",
			name = "Export Directory",
			description = "Directory where JSON snapshot files are written. Leave empty to use the default RuneLite folder."
	)
	default String exportDirectory()
	{
		return "";
	}

	@ConfigItem(
			keyName = "exportIntervalTicks",
			name = "Export Interval",
			description = "Number of game ticks between snapshot exports. Lower values export more often."
	)
	default int exportIntervalTicks()
	{
		return 5;
	}
}
