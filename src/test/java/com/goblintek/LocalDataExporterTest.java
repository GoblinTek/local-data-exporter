package com.goblintek;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LocalDataExporterTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LocalDataExporterPlugin.class);
		RuneLite.main(args);
	}
}