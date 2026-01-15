package com.example.roamersplus;

import com.example.roamersplus.event.RoamerEventHandler;
import com.example.roamersplus.util.PitySystem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(RoamersPlusMod.MODID)
public class RoamersPlusMod {
    public static final String MODID = "roamersplus";
    public static final Logger LOGGER = LogManager.getLogger();

    public RoamersPlusMod(IEventBus modEventBus) {
        LOGGER.info("RoamersPlus initializing...");
        
        // Register mod lifecycle events
        modEventBus.addListener(this::commonSetup);
        
        // Register game events on the NeoForge event bus
        NeoForge.EVENT_BUS.register(RoamerEventHandler.class);
        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("RoamersPlus common setup complete");
    }
    
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        // Clean up all tracking systems to prevent memory leaks
        PitySystem.cleanup();
        RoamerEventHandler.cleanup();
        LOGGER.info("RoamersPlus cleanup complete");
    }
}
