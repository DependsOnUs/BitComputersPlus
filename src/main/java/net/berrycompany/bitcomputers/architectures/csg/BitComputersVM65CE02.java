package net.berrycompany.bitcomputers.architectures.csg;

import com.loomcom.symon.cpus.csg.CPU65CE02;

import li.cil.oc.api.machine.Context;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class BitComputersVM65CE02 {
	// The simulated machine
	public BitComputersMachine65CE02 machine;

	// Allocated cycles per tick
	public int cyclesPerTick;

	public BitComputersVM65CE02(Context context) {
		MinecraftForge.EVENT_BUS.register(this);
		try {
            this.machine = new BitComputersMachine65CE02(context);
			if (context.node().network() != null) {
                this.machine.getCpu().reset();
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to setup BitComputers", e);
		}
	}

	void run() throws Exception {
        CPU65CE02 mCPU = this.machine.getCpu();
        this.machine.getComponentSelector().checkDelay();
		while (mCPU.getCycles() > 0) {
			mCPU.step();
		}

        this.machine.getGioDev().flush();
	}

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		Context context = machine.getContext();
		if (!context.isRunning() && !context.isPaused()) {
			MinecraftForge.EVENT_BUS.unregister(this);
			return;
		}

		if (event.phase == TickEvent.Phase.START) {
            CPU65CE02 mCPU = this.machine.getCpu();
            if (mCPU.getCycles() < this.cyclesPerTick) {
                mCPU.addCycles(this.cyclesPerTick);
            }

            this.machine.getRTC().onServerTick();
        }
	}
}
