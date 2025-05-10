package com.npcdropnotifier;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class NpcDropNotifierTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(NpcDropNotifierPlugin.class);
        RuneLite.main(args);
    }
}
