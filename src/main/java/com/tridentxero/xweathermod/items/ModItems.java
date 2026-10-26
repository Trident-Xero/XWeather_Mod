package com.tridentxero.xweathermod.items;

import com.tridentxero.xweathermod.XWeatherMod;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(XWeatherMod.MOD_ID);

    public static final DeferredItem<Item> XWEATHER = ITEMS.registerSimpleItem("xweather");


    public static void register(IEventBus eventBus)  {
        ITEMS.register(eventBus);
    }
}
