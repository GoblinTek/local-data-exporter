package com.goblintek;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Provides;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
		name = "Local Data Exporter",
		description = "Exports local snapshots for spreadsheets, account tracking, and personal analytics.",
		tags = {"data", "export", "json", "tracking"}
)
public class LocalDataExporterPlugin extends Plugin
{
	private static final String EXPORT_VERSION = "0.4.5";
	private static final int LOGIN_SETTLE_TICKS = 5;

	@Inject
	private Gson gson;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	private int ticksLoggedIn;
	private int ticksSinceExport;

	private String lastRsn = "";
	private Map<String, Object> lastGoodBank;
	private long lastGoodBankTimestamp;
	private Map<String, Object> lastGoodInventory;
	private long lastGoodInventoryTimestamp;
	private Map<String, Object> lastGoodEquipment;
	private long lastGoodEquipmentTimestamp;

	@Provides
	LocalDataExporterConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LocalDataExporterConfig.class);
	}

	@Override
	protected void startUp()
	{
		log.info("Local Data Exporter v{} started.", EXPORT_VERSION);
		resetSessionState();
	}

	@Override
	protected void shutDown()
	{
		log.info("Local Data Exporter stopped.");
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player localPlayer = client.getLocalPlayer();

		if (client.getGameState() != GameState.LOGGED_IN || localPlayer == null)
		{
			ticksLoggedIn = 0;
			ticksSinceExport = 0;
			return;
		}

		String rsn = localPlayer.getName();
		if (rsn != null && !rsn.equals(lastRsn))
		{
			resetAccountCachesForNewRsn(rsn);
		}

		ticksLoggedIn++;
		ticksSinceExport++;

		if (ticksLoggedIn < LOGIN_SETTLE_TICKS)
		{
			return;
		}

		if (ticksSinceExport < getExportIntervalTicks())
		{
			return;
		}

		ticksSinceExport = 0;
		exportSnapshot();
	}

	private void resetSessionState()
	{
		ticksLoggedIn = 0;
		ticksSinceExport = 0;
		lastRsn = "";
		lastGoodBank = null;
		lastGoodBankTimestamp = 0;
		lastGoodInventory = null;
		lastGoodInventoryTimestamp = 0;
		lastGoodEquipment = null;
		lastGoodEquipmentTimestamp = 0;
	}

	private void resetAccountCachesForNewRsn(String rsn)
	{
		lastRsn = rsn;
		lastGoodBank = null;
		lastGoodBankTimestamp = 0;
		lastGoodInventory = null;
		lastGoodInventoryTimestamp = 0;
		lastGoodEquipment = null;
		lastGoodEquipmentTimestamp = 0;
	}

	private void exportSnapshot()
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		String rsn = localPlayer.getName();
		int world = client.getWorld();
		File exportDirectory = getExportDirectory();

		Map<String, Object> skills = buildSkills();
		File accountFile = getAccountFile(exportDirectory, rsn);
		Map<String, Object> previousSnapshot = loadPreviousSnapshot(accountFile);

		Map<String, Object> currentInventory = buildContainer(InventoryID.INVENTORY);
		boolean currentInventoryLoaded = Boolean.TRUE.equals(currentInventory.get("loaded"));
		if (currentInventoryLoaded)
		{
			lastGoodInventory = currentInventory;
			lastGoodInventoryTimestamp = System.currentTimeMillis();
		}
		CachedContainer inventoryCache = chooseContainerForExport(
				"inventory",
				currentInventory,
				lastGoodInventory,
				lastGoodInventoryTimestamp,
				previousSnapshot
		);
		lastGoodInventory = inventoryCache.container;
		lastGoodInventoryTimestamp = inventoryCache.lastSeenTimestamp;
		Map<String, Object> inventory = inventoryCache.container;

		Map<String, Object> currentEquipment = buildContainer(InventoryID.EQUIPMENT);
		boolean currentEquipmentLoaded = Boolean.TRUE.equals(currentEquipment.get("loaded"));
		if (currentEquipmentLoaded)
		{
			lastGoodEquipment = currentEquipment;
			lastGoodEquipmentTimestamp = System.currentTimeMillis();
		}
		CachedContainer equipmentCache = chooseContainerForExport(
				"equipment",
				currentEquipment,
				lastGoodEquipment,
				lastGoodEquipmentTimestamp,
				previousSnapshot
		);
		lastGoodEquipment = equipmentCache.container;
		lastGoodEquipmentTimestamp = equipmentCache.lastSeenTimestamp;
		Map<String, Object> equipment = equipmentCache.container;

		Map<String, Object> currentBank = buildContainer(InventoryID.BANK);
		boolean currentBankLoaded = Boolean.TRUE.equals(currentBank.get("loaded"));
		if (currentBankLoaded)
		{
			lastGoodBank = currentBank;
			lastGoodBankTimestamp = System.currentTimeMillis();
		}
		CachedContainer bankCache = chooseContainerForExport(
				"bank",
				currentBank,
				lastGoodBank,
				lastGoodBankTimestamp,
				previousSnapshot
		);
		lastGoodBank = bankCache.container;
		lastGoodBankTimestamp = bankCache.lastSeenTimestamp;

		Map<String, Object> bankForExport = bankCache.container;
		boolean bankLoadedForExport = bankCache.loaded;
		boolean bankFromCache = bankCache.fromCache;

		long bankValue = bankLoadedForExport ? getLong(bankForExport.get("value")) : 0;
		int bankItemCount = bankLoadedForExport ? getInt(bankForExport.get("itemCount")) : 0;

		long inventoryValue = inventoryCache.loaded ? getLong(inventory.get("value")) : 0;
		long equipmentValue = equipmentCache.loaded ? getLong(equipment.get("value")) : 0;
		long carriedValue = inventoryValue + equipmentValue;
		long knownAccountValue = bankValue + carriedValue;

		Map<String, Object> grandExchange = chooseSectionForExport("grandExchange", buildGrandExchangeOffers(), previousSnapshot);
		long grandExchangeAccountValueEstimate = getLong(grandExchange.get("accountValueEstimate"));

		Map<String, Object> snapshot = new LinkedHashMap<>();

		snapshot.put("version", EXPORT_VERSION);
		snapshot.put("timestamp", System.currentTimeMillis());
		snapshot.put("timestampIso", Instant.now().toString());

		snapshot.put("rsn", rsn);
		snapshot.put("world", world);
		snapshot.put("gameState", client.getGameState().name());
		snapshot.put("combatLevel", localPlayer.getCombatLevel());
		snapshot.put("totalLevel", calculateTotalLevel());
		snapshot.put("totalXp", calculateTotalXp());

		snapshot.put("fps", client.getFPS());
		snapshot.put("exportIntervalTicks", getExportIntervalTicks());
		snapshot.put("exportDirectory", exportDirectory.getAbsolutePath());

		snapshot.put("status", buildStatus());
		snapshot.put("location", buildLocation(localPlayer));
		snapshot.put("animation", buildAnimation(localPlayer));

		snapshot.put("bankLoaded", bankLoadedForExport);
		snapshot.put("bankFromCache", bankFromCache);
		snapshot.put("bankValue", bankValue);
		snapshot.put("bankItemCount", bankItemCount);
		snapshot.put("bankLastSeenTimestamp", lastGoodBankTimestamp);

		snapshot.put("inventoryLoaded", inventoryCache.loaded);
		snapshot.put("inventoryFromCache", inventoryCache.fromCache);
		snapshot.put("inventoryValue", inventoryValue);
		snapshot.put("inventoryLastSeenTimestamp", inventoryCache.lastSeenTimestamp);
		snapshot.put("equipmentLoaded", equipmentCache.loaded);
		snapshot.put("equipmentFromCache", equipmentCache.fromCache);
		snapshot.put("equipmentValue", equipmentValue);
		snapshot.put("equipmentLastSeenTimestamp", equipmentCache.lastSeenTimestamp);
		snapshot.put("carriedValue", carriedValue);
		snapshot.put("knownAccountValue", knownAccountValue);
		snapshot.put("grandExchangeAccountValueEstimate", grandExchangeAccountValueEstimate);
		snapshot.put("knownAccountValueWithGeEstimate", knownAccountValue + grandExchangeAccountValueEstimate);

		snapshot.put("skills", skills);
		snapshot.put("inventory", inventory);
		snapshot.put("equipment", equipment);
		snapshot.put("bank", bankForExport);
		snapshot.put("grandExchange", grandExchange);
		snapshot.put("quests", chooseSectionForExport("quests", buildQuests(), previousSnapshot));
		snapshot.put("achievementDiaries", chooseSectionForExport("achievementDiaries", buildAchievementDiaries(), previousSnapshot));

		writeJson(exportDirectory, rsn, snapshot);

		log.info(
				"Local Data Exporter v{} | rsn={} | world={} | totalLevel={} | totalXp={} | bankValue={} | geAccountValueEstimate={}",
				EXPORT_VERSION,
				rsn,
				world,
				snapshot.get("totalLevel"),
				snapshot.get("totalXp"),
				bankValue,
				grandExchangeAccountValueEstimate
		);
	}

	private Map<String, Object> buildStatus()
	{
		Map<String, Object> status = new LinkedHashMap<>();

		int runEnergyRaw = client.getEnergy();
		status.put("runEnergy", runEnergyRaw);
		status.put("runEnergyPercent", runEnergyRaw / 100.0);
		status.put("weight", client.getWeight());
		status.put("specialAttackPercent", client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT));
		status.put("specialAttackEnabled", client.getVarpValue(VarPlayer.SPECIAL_ATTACK_ENABLED) == 1);

		return status;
	}

	private Map<String, Object> buildLocation(Player localPlayer)
	{
		Map<String, Object> location = new LinkedHashMap<>();
		WorldPoint worldPoint = localPlayer.getWorldLocation();

		if (worldPoint == null)
		{
			location.put("loaded", false);
			return location;
		}

		location.put("loaded", true);
		location.put("worldX", worldPoint.getX());
		location.put("worldY", worldPoint.getY());
		location.put("plane", worldPoint.getPlane());
		location.put("regionId", worldPoint.getRegionID());
		location.put("regionX", worldPoint.getRegionX());
		location.put("regionY", worldPoint.getRegionY());

		return location;
	}

	private Map<String, Object> buildAnimation(Player localPlayer)
	{
		Map<String, Object> animation = new LinkedHashMap<>();
		animation.put("current", localPlayer.getAnimation());
		animation.put("pose", localPlayer.getPoseAnimation());
		animation.put("idlePose", localPlayer.getIdlePoseAnimation());
		animation.put("orientation", localPlayer.getOrientation());
		animation.put("currentOrientation", localPlayer.getCurrentOrientation());
		return animation;
	}

	private Map<String, Object> buildGrandExchangeOffers()
	{
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, Object> offersOut = new LinkedHashMap<>();

		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		boolean loaded = offers != null;
		int activeCount = 0;
		long listedValueEstimate = 0;
		long accountValueEstimate = 0;

		result.put("loaded", loaded);

		if (offers != null)
		{
			for (int slot = 0; slot < offers.length; slot++)
			{
				GrandExchangeOffer offer = offers[slot];
				if (offer == null || offer.getState() == null || "EMPTY".equals(offer.getState().name()))
				{
					continue;
				}

				int itemId = offer.getItemId();
				int listedPrice = offer.getPrice();
				int marketPrice = itemId > 0 ? itemManager.getItemPrice(itemId) : 0;
				int totalQuantity = offer.getTotalQuantity();
				int completedQuantity = offer.getQuantitySold();
				int remainingQuantity = Math.max(0, totalQuantity - completedQuantity);
				int spent = offer.getSpent();
				String state = offer.getState().name();

				long offerListedValueEstimate = (long) listedPrice * totalQuantity;
				long offerAccountValueEstimate = estimateGrandExchangeAccountValue(
						state,
						marketPrice,
						listedPrice,
						totalQuantity,
						completedQuantity,
						remainingQuantity,
						spent
				);

				activeCount++;
				listedValueEstimate += offerListedValueEstimate;
				accountValueEstimate += offerAccountValueEstimate;

				Map<String, Object> offerOut = new LinkedHashMap<>();
				offerOut.put("slot", slot);
				offerOut.put("state", state);
				offerOut.put("itemId", itemId);
				offerOut.put("itemName", itemId > 0 ? itemManager.getItemComposition(itemId).getName() : "");
				offerOut.put("listedPrice", listedPrice);
				offerOut.put("marketPrice", marketPrice);
				offerOut.put("totalQuantity", totalQuantity);
				offerOut.put("completedQuantity", completedQuantity);
				offerOut.put("remainingQuantity", remainingQuantity);
				offerOut.put("spent", spent);
				offerOut.put("listedValueEstimate", offerListedValueEstimate);
				offerOut.put("accountValueEstimate", offerAccountValueEstimate);

				offersOut.put(String.valueOf(slot), offerOut);
			}
		}

		result.put("activeCount", activeCount);
		result.put("listedValueEstimate", listedValueEstimate);
		result.put("accountValueEstimate", accountValueEstimate);
		result.put("offers", offersOut);

		return result;
	}

	private long estimateGrandExchangeAccountValue(
			String state,
			int marketPrice,
			int listedPrice,
			int totalQuantity,
			int completedQuantity,
			int remainingQuantity,
			int spent
	)
	{
		if (state != null && state.contains("SELL"))
		{
			return (long) spent + ((long) marketPrice * remainingQuantity);
		}

		if (state != null && state.contains("BUY"))
		{
			return ((long) marketPrice * completedQuantity) + ((long) listedPrice * remainingQuantity);
		}

		return (long) marketPrice * totalQuantity;
	}

	private Map<String, Object> buildQuests()
	{
		Map<String, Object> quests = new LinkedHashMap<>();
		Map<String, Object> entries = new LinkedHashMap<>();

		int notStarted = 0;
		int inProgress = 0;
		int finished = 0;
		int unknown = 0;

		for (Quest quest : Quest.values())
		{
			QuestState state;

			try
			{
				state = quest.getState(client);
			}
			catch (RuntimeException e)
			{
				state = null;
			}

			String stateName = state != null ? state.name() : "UNKNOWN";

			if (state == QuestState.NOT_STARTED)
			{
				notStarted++;
			}
			else if (state == QuestState.IN_PROGRESS)
			{
				inProgress++;
			}
			else if (state == QuestState.FINISHED)
			{
				finished++;
			}
			else
			{
				unknown++;
			}

			Map<String, Object> questData = new LinkedHashMap<>();
			questData.put("name", quest.getName());
			questData.put("state", stateName);

			entries.put(quest.name(), questData);
		}

		quests.put("notStarted", notStarted);
		quests.put("inProgress", inProgress);
		quests.put("finished", finished);
		quests.put("unknown", unknown);
		quests.put("total", notStarted + inProgress + finished + unknown);
		quests.put("entries", entries);

		return quests;
	}

	private Map<String, Object> buildAchievementDiaries()
	{
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, Object> regions = new LinkedHashMap<>();

		int completedTiers = 0;
		int totalTiers = 0;

		completedTiers += addAchievementDiaryRegion(regions, "ardougne", "Ardougne", VarbitID.ARDOUGNE_EASY_REWARD, VarbitID.ARDOUGNE_MEDIUM_REWARD, VarbitID.ARDOUGNE_HARD_REWARD, VarbitID.ARDOUGNE_ELITE_REWARD);
		completedTiers += addAchievementDiaryRegion(regions, "desert", "Desert", VarbitID.DESERT_EASY_REWARD, VarbitID.DESERT_MEDIUM_REWARD, VarbitID.DESERT_HARD_REWARD, VarbitID.DESERT_ELITE_REWARD);
		completedTiers += addAchievementDiaryRegion(regions, "falador", "Falador", VarbitID.FALADOR_EASY_REWARD, VarbitID.FALADOR_MEDIUM_REWARD, VarbitID.FALADOR_HARD_REWARD, VarbitID.FALADOR_ELITE_REWARD);
		completedTiers += addAchievementDiaryRegion(regions, "fremennik", "Fremennik", VarbitID.FREMENNIK_EASY_REWARD, VarbitID.FREMENNIK_MEDIUM_REWARD, VarbitID.FREMENNIK_HARD_REWARD, VarbitID.FREMENNIK_ELITE_REWARD);
		completedTiers += addAchievementDiaryRegion(regions, "kandarin", "Kandarin", VarbitID.KANDARIN_EASY_REWARD, VarbitID.KANDARIN_MEDIUM_REWARD, VarbitID.KANDARIN_HARD_REWARD, VarbitID.KANDARIN_ELITE_REWARD);
		completedTiers += addAchievementDiaryRegion(regions, "karamja", "Karamja", VarbitID.ATJUN_EASY_REWARD, VarbitID.ATJUN_MED_REWARD, VarbitID.ATJUN_HARD_REWARD, VarbitID.KARAMJA_ELITE_REWARD);
		completedTiers += addAchievementDiaryRegion(regions, "kourend_kebos", "Kourend & Kebos", VarbitID.KOUREND_EASY_REWARD, VarbitID.KOUREND_MEDIUM_REWARD, VarbitID.KOUREND_HARD_REWARD, VarbitID.KOUREND_ELITE_REWARD);
		completedTiers += addAchievementDiaryRegion(regions, "lumbridge_draynor", "Lumbridge & Draynor", VarbitID.LUMBRIDGE_EASY_REWARD, VarbitID.LUMBRIDGE_MEDIUM_REWARD, VarbitID.LUMBRIDGE_HARD_REWARD, VarbitID.LUMBRIDGE_ELITE_REWARD);
		completedTiers += addAchievementDiaryRegion(regions, "morytania", "Morytania", VarbitID.MORYTANIA_EASY_REWARD, VarbitID.MORYTANIA_MEDIUM_REWARD, VarbitID.MORYTANIA_HARD_REWARD, VarbitID.MORYTANIA_ELITE_REWARD);
		completedTiers += addAchievementDiaryRegion(regions, "varrock", "Varrock", VarbitID.VARROCK_EASY_REWARD, VarbitID.VARROCK_MEDIUM_REWARD, VarbitID.VARROCK_HARD_REWARD, VarbitID.VARROCK_ELITE_REWARD);
		completedTiers += addAchievementDiaryRegion(regions, "western_provinces", "Western Provinces", VarbitID.WESTERN_EASY_REWARD, VarbitID.WESTERN_MEDIUM_REWARD, VarbitID.WESTERN_HARD_REWARD, VarbitID.WESTERN_ELITE_REWARD);
		completedTiers += addAchievementDiaryRegion(regions, "wilderness", "Wilderness", VarbitID.WILDERNESS_EASY_REWARD, VarbitID.WILDERNESS_MEDIUM_REWARD, VarbitID.WILDERNESS_HARD_REWARD, VarbitID.WILDERNESS_ELITE_REWARD);

		totalTiers = regions.size() * 4;

		result.put("source", "RuneLite VarbitID achievement diary reward completion varbits");
		result.put("completedTierCount", completedTiers);
		result.put("totalTierCount", totalTiers);
		result.put("completionPercent", totalTiers > 0 ? (completedTiers * 100.0) / totalTiers : 0.0);
		result.put("regions", regions);

		return result;
	}

	private int addAchievementDiaryRegion(
			Map<String, Object> regions,
			String key,
			String name,
			int easyVarbit,
			int mediumVarbit,
			int hardVarbit,
			int eliteVarbit
	)
	{
		Map<String, Object> region = new LinkedHashMap<>();
		region.put("name", name);
		region.put("easy", buildAchievementDiaryTier(easyVarbit));
		region.put("medium", buildAchievementDiaryTier(mediumVarbit));
		region.put("hard", buildAchievementDiaryTier(hardVarbit));
		region.put("elite", buildAchievementDiaryTier(eliteVarbit));

		int completed = 0;
		completed += isAchievementDiaryTierComplete(easyVarbit) ? 1 : 0;
		completed += isAchievementDiaryTierComplete(mediumVarbit) ? 1 : 0;
		completed += isAchievementDiaryTierComplete(hardVarbit) ? 1 : 0;
		completed += isAchievementDiaryTierComplete(eliteVarbit) ? 1 : 0;

		region.put("completedTierCount", completed);
		region.put("totalTierCount", 4);
		region.put("allComplete", completed == 4);

		regions.put(key, region);
		return completed;
	}

	private Map<String, Object> buildAchievementDiaryTier(int varbitId)
	{
		Map<String, Object> tier = new LinkedHashMap<>();
		int value = client.getVarbitValue(varbitId);

		tier.put("complete", value > 0);
		tier.put("value", value);

		return tier;
	}

	private boolean isAchievementDiaryTierComplete(int varbitId)
	{
		return client.getVarbitValue(varbitId) > 0;
	}

	private Map<String, Object> buildSkills()
	{
		Map<String, Object> skills = new LinkedHashMap<>();

		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}

			Map<String, Object> skillData = new LinkedHashMap<>();
			skillData.put("level", client.getRealSkillLevel(skill));
			skillData.put("boostedLevel", client.getBoostedSkillLevel(skill));
			skillData.put("xp", client.getSkillExperience(skill));

			skills.put(skill.getName(), skillData);
		}

		return skills;
	}

	private int calculateTotalLevel()
	{
		int total = 0;

		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}

			total += client.getRealSkillLevel(skill);
		}

		return total;
	}

	private long calculateTotalXp()
	{
		long total = 0;

		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}

			total += client.getSkillExperience(skill);
		}

		return total;
	}

	private Map<String, Object> buildContainer(InventoryID inventoryId)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		ItemContainer container = client.getItemContainer(inventoryId);

		boolean loaded = container != null;
		result.put("loaded", loaded);

		long totalValue = 0;
		int itemCount = 0;
		Map<String, Object> items = new LinkedHashMap<>();

		if (container != null)
		{
			Item[] containerItems = container.getItems();

			for (int slot = 0; slot < containerItems.length; slot++)
			{
				Item item = containerItems[slot];
				if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
				{
					continue;
				}

				int id = item.getId();
				int quantity = item.getQuantity();
				int price = itemManager.getItemPrice(id);
				long value = (long) price * quantity;

				totalValue += value;
				itemCount++;

				Map<String, Object> itemData = new LinkedHashMap<>();
				itemData.put("slot", slot);
				itemData.put("id", id);
				itemData.put("name", itemManager.getItemComposition(id).getName());
				itemData.put("quantity", quantity);
				itemData.put("price", price);
				itemData.put("value", value);

				items.put(String.valueOf(slot), itemData);
			}
		}

		result.put("value", totalValue);
		result.put("itemCount", itemCount);
		result.put("items", items);

		return result;
	}

	private CachedContainer chooseContainerForExport(
			String key,
			Map<String, Object> currentContainer,
			Map<String, Object> memoryContainer,
			long memoryTimestamp,
			Map<String, Object> previousSnapshot
	)
	{
		if (Boolean.TRUE.equals(currentContainer.get("loaded")))
		{
			return new CachedContainer(currentContainer, true, false, System.currentTimeMillis());
		}

		if (memoryContainer != null && Boolean.TRUE.equals(memoryContainer.get("loaded")))
		{
			return new CachedContainer(memoryContainer, true, true, memoryTimestamp);
		}

		CachedContainer diskContainer = loadContainerFromPreviousSnapshot(key, previousSnapshot);
		if (diskContainer != null)
		{
			return diskContainer;
		}

		return new CachedContainer(currentContainer, false, false, 0);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> loadPreviousSnapshot(File accountFile)
	{
		if (accountFile == null || !accountFile.exists())
		{
			return null;
		}

		try (FileReader reader = new FileReader(accountFile))
		{
			Object parsed = gson.fromJson(reader, Map.class);
			if (parsed instanceof Map)
			{
				return (Map<String, Object>) parsed;
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Local Data Exporter could not read previous snapshot {}", accountFile.getAbsolutePath(), e);
		}

		return null;
	}

	@SuppressWarnings("unchecked")
	private CachedContainer loadContainerFromPreviousSnapshot(String key, Map<String, Object> previousSnapshot)
	{
		if (previousSnapshot == null)
		{
			return null;
		}

		Object containerObject = previousSnapshot.get(key);
		if (!(containerObject instanceof Map))
		{
			return null;
		}

		Map<String, Object> container = (Map<String, Object>) containerObject;
		if (!Boolean.TRUE.equals(container.get("loaded")))
		{
			return null;
		}

		long timestamp = getLong(previousSnapshot.get(key + "LastSeenTimestamp"));
		if (timestamp <= 0)
		{
			timestamp = getLong(previousSnapshot.get("timestamp"));
		}

		return new CachedContainer(container, true, true, timestamp);
	}

	private File getAccountFile(File dir, String rsn)
	{
		return new File(dir, getSafeFileName(rsn) + ".json");
	}

	private String getSafeFileName(String rsn)
	{
		return rsn.replaceAll("[^a-zA-Z0-9 _-]", "_");
	}

	private static class CachedContainer
	{
		private final Map<String, Object> container;
		private final boolean loaded;
		private final boolean fromCache;
		private final long lastSeenTimestamp;

		private CachedContainer(Map<String, Object> container, boolean loaded, boolean fromCache, long lastSeenTimestamp)
		{
			this.container = container;
			this.loaded = loaded;
			this.fromCache = fromCache;
			this.lastSeenTimestamp = lastSeenTimestamp;
		}
	}


	@SuppressWarnings("unchecked")
	private Map<String, Object> chooseSectionForExport(
			String key,
			Map<String, Object> currentSection,
			Map<String, Object> previousSnapshot
	)
	{
		boolean currentUnavailable = currentSection == null
				|| currentSection.isEmpty()
				|| Boolean.FALSE.equals(currentSection.get("loaded"));

		if (!currentUnavailable)
		{
			return currentSection;
		}

		if (previousSnapshot != null)
		{
			Object previousSection = previousSnapshot.get(key);
			if (previousSection instanceof Map)
			{
				return (Map<String, Object>) previousSection;
			}
		}

		return currentSection != null ? currentSection : new LinkedHashMap<>();
	}


	private void writeJson(File dir, String rsn, Map<String, Object> snapshot)
	{
		if (!dir.exists() && !dir.mkdirs())
		{
			log.warn("Local Data Exporter could not create directory: {}", dir.getAbsolutePath());
			return;
		}

		File latestFile = new File(dir, "latest.json");
		File accountFile = getAccountFile(dir, rsn);

		try
		{
			writeJsonFile(latestFile, snapshot);
			writeJsonFile(accountFile, snapshot);
		}
		catch (IOException e)
		{
			log.warn("Local Data Exporter failed to write JSON.", e);
		}
	}

	private File getExportDirectory()
	{
		return new File(RuneLite.RUNELITE_DIR, "local-data-exporter");
	}

	private int getExportIntervalTicks()
	{
		return Math.max(1, getConfig().exportIntervalTicks());
	}

	private LocalDataExporterConfig getConfig()
	{
		return configManager.getConfig(LocalDataExporterConfig.class);
	}

	private void writeJsonFile(File file, Map<String, Object> snapshot) throws IOException
	{
		try (FileWriter writer = new FileWriter(file))
		{
			gson.toJson(snapshot, writer);
		}
	}

	private long getLong(Object value)
	{
		if (value instanceof Number)
		{
			return ((Number) value).longValue();
		}

		return 0L;
	}

	private int getInt(Object value)
	{
		if (value instanceof Number)
		{
			return ((Number) value).intValue();
		}

		return 0;
	}
}
