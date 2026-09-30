package com.ldtteam.structurize.event;

import net.minecraft.world.InteractionResult;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * BS-C6: a tool's own middle-click action must not also run vanilla pick-block (1.21 behaviour).
 */
public class ClientEventSubscriberPickTest
{
    @Test
    public void toolActionDoesNotAlsoRunVanillaPick()
    {
        for (final InteractionResult result : new InteractionResult[] {InteractionResult.SUCCESS, InteractionResult.SUCCESS_SERVER, InteractionResult.CONSUME})
        {
            assertTrue(result + " goes to the server", ClientEventSubscriber.sendsPickToServer(result));
            assertFalse(result + " must not run vanilla pick-block", ClientEventSubscriber.runsVanillaPick(result));
        }
    }

    @Test
    public void passRunsVanillaPickOnlyAndFailDoesNothing()
    {
        assertTrue(ClientEventSubscriber.runsVanillaPick(InteractionResult.PASS));
        assertFalse(ClientEventSubscriber.sendsPickToServer(InteractionResult.PASS));
        assertFalse(ClientEventSubscriber.runsVanillaPick(InteractionResult.FAIL));
        assertFalse(ClientEventSubscriber.sendsPickToServer(InteractionResult.FAIL));
    }
}
