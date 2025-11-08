package net.berrycompany.bitcomputers.architectures.wdc;

import com.loomcom.symon.cpus.wdc.CPU65C02S;
import com.loomcom.symon.cpus.wdc.CPU65C02State;
import li.cil.oc.Settings;
import li.cil.oc.api.Driver;
import li.cil.oc.api.driver.item.Memory;
import li.cil.oc.api.driver.item.Processor;
import li.cil.oc.api.machine.*;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Node;
import li.cil.oc.common.SaveHandler;
import li.cil.oc.server.PacketSender;
import li.cil.oc.server.machine.Callbacks;
import net.berrycompany.bitcomputers.BitComputers;
import net.berrycompany.bitcomputers.BitComputersArchitecture;
import net.berrycompany.bitcomputers.BitComputersConfig;
import net.berrycompany.bitcomputers.devices.opencomputers.BankSelector;
import net.berrycompany.bitcomputers.exceptions.CallSynchronizedException;
import net.berrycompany.bitcomputers.util.ValueManager;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.MinecraftForge;
import org.apache.commons.io.IOUtils;
import scala.Option;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@SuppressWarnings("unused")
@Architecture.Name("WDC 65C02S")
public class BitComputersArchitecture65C02S extends BitComputersArchitecture {
	private final Machine machine;

	private BitComputersVM65C02S vm;

	private boolean initialized = false;

	private boolean inSynchronizedCall = false;

	private CallSynchronizedException syncCall;

	/** The constructor must have exactly this signature. */
	public BitComputersArchitecture65C02S(Machine machine) {
		this.machine = machine;
	}

	@Override
	public boolean isInitialized() {
		return initialized;
	}

	private static int calculateMemory(Iterable<ItemStack> components) {
		int memory = 0;
		for (ItemStack component : components) {
			if (Driver.driverFor(component) instanceof Memory) {
				Memory memdrv = (Memory) Driver.driverFor(component);
				memory += memdrv.amount(component) * 1024;
			}
		}
		return Math.min(Math.max(memory, 0), Settings.get().maxTotalRam());
	}

	@Override
	public boolean recomputeMemory(Iterable<ItemStack> components) {
		if (vm != null) {
			int memory = calculateMemory(components);
			vm.machine.resize(memory);
		}
		return true;
	}

	@SuppressWarnings("unused")
	@Override
	public boolean initialize() {
		// Set up new VM here
		vm = new BitComputersVM65C02S(machine);
		BankSelector banksel = this.vm.machine.getBankSelector();
		int memory = calculateMemory(this.machine.host().internalComponents());
		vm.machine.resize(memory);
		vm.machine.reset();
		for (ItemStack component : machine.host().internalComponents()) {
			if (Driver.driverFor(component) instanceof Processor) {
				vm.cyclesPerTick = BitComputersConfig.debugCpuSlowDown ? 10 : (Driver.driverFor(component).tier(component) + 1) * BitComputersConfig.clocksPerTick;
				break;
			}
		}
		try {
			PacketSender.sendSound(machine.host().world(), machine.host().xPosition(), machine.host().yPosition(), machine.host().zPosition(), ".");
		} catch (Throwable ignored) {
		}
		initialized = true;
		return true;
	}

	@Override
	public void close() {
		ValueManager.removeAll(this.machine);
		if (vm != null) {
			MinecraftForge.EVENT_BUS.unregister(vm.machine);
			vm = null;
		}
	}

	@Override
	public ExecutionResult runThreaded(boolean isSynchronizedReturn) {
		try {
			if (!isSynchronizedReturn) {
				// Since our machine is a memory mapped one, parse signals here
				Signal signal;
				while (true) {
					signal = machine.popSignal();
					if (signal != null) {
						vm.machine.getBus().onSignal(signal);
					} else
						break;
				}
			}
			vm.run();

			return new ExecutionResult.Sleep(0);
		} catch (CallSynchronizedException e) {
			if (e.getCleanup() != null) {
				if (BitComputersConfig.debugCpuTraceLog) // Exceptions thrown cause BitComputersVM to skip trace logging.
					BitComputers.log.info("[Cpu] " + vm.machine.getCpu().getCpuState().toTraceEvent());
				syncCall = e;
			}
			return new ExecutionResult.SynchronizedCall();
		} catch (LimitReachedException e) {
			return new ExecutionResult.SynchronizedCall();
		} catch (Throwable t) {
			return new ExecutionResult.Error(t.toString());
		}
	}

	@Override
	public void runSynchronized() {
		if (syncCall != null) {
			// Nice clean method for us to avoid multiple bus writes
			Object thing = syncCall.getThing();
			CallSynchronizedException.Cleanup cleanup = syncCall.getCleanup();
			try {
				Object[] results = null;
				if (thing instanceof String)
					results = machine.invoke((String) thing, syncCall.getMethod(), syncCall.getArgs());
				else if (thing instanceof Value)
					results = machine.invoke((Value) thing, syncCall.getMethod(), syncCall.getArgs());
				cleanup.run(results, machine);
			} catch (Exception e) {
				cleanup.error(e);
			}
			cleanup.finish();
			syncCall = null;
		} else {
			// Attempt to invoke again by re-executing the last instruction
			inSynchronizedCall = true;
			CPU65C02S cpu = vm.machine.getCpu();
			((CPU65C02State) cpu.getCpuState()).pc = ((CPU65C02State) cpu.getCpuState()).lastPc;
			cpu.step();
			inSynchronizedCall = false;
		}
	}

	@Override
	public void onSignal() {
	}

	@Override
	public void onConnect() {
	}

	public Object[] invoke(String address, String method, Object[] args) throws Exception {
		if (!inSynchronizedCall) {
			Node node = machine.node().network().node(address);
			if (node instanceof Component) {
				Callback callback = ((Component) node).annotation(method);
				if (callback != null && !callback.direct())
					throw new CallSynchronizedException(address, method, args);
			}
		}
		try {
			return machine.invoke(address, method, args);
		} catch (LimitReachedException e) {
			throw new CallSynchronizedException(address, method, args);
		}
	}

	public Object[] invoke(Value value, String method, Object[] args) throws Exception {
		if (!inSynchronizedCall) {
			Option<Callbacks.Callback> option = Callbacks.apply(value).get(method);
			if (option != null) {
				Callbacks.Callback callback = option.get();
				if (callback != null && !callback.annotation().direct())
					throw new CallSynchronizedException(value, method, args);
			}
		}
		try {
			return machine.invoke(value, method, args);
		} catch (LimitReachedException e) {
			throw new CallSynchronizedException(value, method, args);
		}
	}

	@Override
	public void load(NBTTagCompound nbt) {
		// Restore Machine

		// Restore Memory
		byte[] gzmem = SaveHandler.load(nbt, this.machine.node().address() + "_memory");
		if (gzmem.length > 0) {
			try {
				ByteArrayInputStream bais = new ByteArrayInputStream(gzmem);
				GZIPInputStream gzis = new GZIPInputStream(bais);
				byte[] mem = IOUtils.toByteArray(gzis);
				IOUtils.closeQuietly(gzis);
				vm.machine.resize(mem.length);
				for (int i = 0; i < mem.length; i++)
					vm.machine.writeMem(i, mem[i]);
			} catch (IOException e) {
				BitComputers.log.error("Failed to decompress memory from disk.", e);
			}
		}

		// Restore CPU
		if (nbt.hasKey("cpu")) {
			CPU65C02S mCPU = vm.machine.getCpu();
			CPU65C02State cpuState = (CPU65C02State) mCPU.getCpuState();
			NBTTagCompound cpuTag = nbt.getCompoundTag("cpu");
			cpuState.a = cpuTag.getInteger("rA");
			mCPU.setProcessorStatus(cpuTag.getInteger("rP"));
			cpuState.pc = cpuTag.getInteger("rPC");
			cpuState.sp = cpuTag.getInteger("rSP");
			cpuState.x = cpuTag.getInteger("rX");
			cpuState.y = cpuTag.getInteger("rY");
			cpuState.irqAsserted = cpuTag.getBoolean("iI");
			cpuState.nmiAsserted = cpuTag.getBoolean("iN");
			cpuState.dead = cpuTag.getBoolean("sD");
			cpuState.sleep = cpuTag.getBoolean("sS");
		}

		// Restore Values
		if (nbt.hasKey("values"))
			ValueManager.load(nbt.getCompoundTag("values"), this.machine);

		vm.machine.getBus().load(nbt);
	}

	@Override
	public void save(NBTTagCompound nbt) {
		// Persist Machine

		// Persist Memory
		byte[] mem = new byte[vm.machine.getMemsize()];
		for (int i = 0; i < mem.length; i++)
			mem[i] = vm.machine.readMem(i);
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			GZIPOutputStream gzos = new GZIPOutputStream(baos);
			gzos.write(mem);
			gzos.close();
			SaveHandler.scheduleSave(machine.host(), nbt, machine.node().address() + "_memory", baos.toByteArray());
		} catch (IOException e) {
			BitComputers.log.error("Failed to compress memory to disk", e);
		}

		// Persist CPU
		CPU65C02S mCPU = vm.machine.getCpu();
		if (mCPU != null) {
			NBTTagCompound cpuTag = getNbtTagCompound(mCPU);
			nbt.setTag("cpu", cpuTag);
		}

		// Persist Values
		NBTTagCompound valueTag = new NBTTagCompound();
		ValueManager.save(valueTag, this.machine);
		nbt.setTag("values", valueTag);

		vm.machine.getBus().save(nbt);
	}

	private static NBTTagCompound getNbtTagCompound(CPU65C02S mCPU) {
		CPU65C02State cpuState = (CPU65C02State) mCPU.getCpuState();
		NBTTagCompound cpuTag = new NBTTagCompound();
		cpuTag.setInteger("rA", cpuState.a);
		cpuTag.setInteger("rP", mCPU.getProcessorStatus());
		cpuTag.setInteger("rPC", cpuState.pc);
		cpuTag.setInteger("rSP", cpuState.sp);
		cpuTag.setInteger("rX", cpuState.x);
		cpuTag.setInteger("rY", cpuState.y);
		cpuTag.setBoolean("iI", cpuState.irqAsserted);
		cpuTag.setBoolean("iN", cpuState.nmiAsserted);
		cpuTag.setBoolean("sD", cpuState.dead);
		cpuTag.setBoolean("sS", cpuState.sleep);
		return cpuTag;
	}
}
