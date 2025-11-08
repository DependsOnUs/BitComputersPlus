/*
 * Copyright (c) 2016 Seth J. Morabito <web@loomcom.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.loomcom.symon;

import java.util.ArrayList;

import com.loomcom.symon.devices.Device;

import net.berrycompany.bitcomputers.BitComputers;
import net.berrycompany.bitcomputers.BitComputersConfig;
import net.berrycompany.bitcomputers.BitComputersMachine;
import net.berrycompany.bitcomputers.api.IBitComputersDevice;
import net.berrycompany.bitcomputers.devices.opencomputers.BankSelector;
import li.cil.oc.api.machine.Signal;
import net.minecraft.nbt.NBTTagCompound;

/*
 * This is not an original/modified Symon file, this class exists in Symon's
 * package because it replaced an implementation of Symon's old Bus.
 */
@SuppressWarnings("unused")
public class Bus {

	// EEPROM data+code at $EF00-$FFFF
	private static final int EEPROM_DATA_BASE = 0xEF00;
	private static final int EEPROM_CODE_BASE = 0xF000;

	private BitComputersMachine machine;
	private final ArrayList<Device> deviceList = new ArrayList<>();

	public int read(int address) {
		address &= 0xFFFF;
		if (address < 0xE000 || address >= EEPROM_CODE_BASE) {
			int select = address >>> 12;
			int bankMask = machine.getComponentSelector().getMask();
			if (select == 15 && (bankMask & (1 << 4)) == 0) {
				if (BitComputersConfig.debugEEPROMReads)
					BitComputers.log.info(String.format("[Bus] EEPROM Read $%04X", address));
				return machine.getEEPROM().read(address - EEPROM_DATA_BASE) & 0xFF; // This reads EEPROM code but offset must be data
			} else if (select >= 10 && select <= 13 && (bankMask & (1 << (select - 10))) == 0) {
				if (BitComputersConfig.debugComponentReads)
					BitComputers.log.info(String.format("[Bus] Component Read $%04X", address));
				int index = address >>> 8;
				index = (0xD0 - (index & 0xF0)) | (index & 0x0F);
				IBitComputersDevice device = machine.getComponentSelector().getComponent(index);
				if (device != null)
					return device.read(machine.getContext(), address & 0xFF) & 0xFF;
			} else {
				if (BitComputersConfig.debugMemoryReads)
					BitComputers.log.info(String.format("[Bus] Memory Read $%04X", address));
				BankSelector banksel = machine.getBankSelector();
				int memaddr = (banksel.bankSelect[select] << 12) | (address & 0xFFF);
				if (memaddr < machine.getMemsize())
					return machine.readMem(memaddr) & 0xFF;
			}
			return 0;
		}
		if (BitComputersConfig.debugDeviceReads)
			BitComputers.log.info(String.format("[Bus] Device Read $%04X", address));
		for (Device device : deviceList) {
			MemoryRange memoryRange = device.getMemoryRange();
			if (address >= memoryRange.startAddress && address <= memoryRange.endAddress) {
				return device.read(address - memoryRange.startAddress) & 0xFF;
			}
		}
		return 0;
	}

	public void write(int address, int data) {
		address &= 0xFFFF;
		data &= 0xFF;
		if (address < 0xE000 || address >= EEPROM_CODE_BASE) {
			int select = address >>> 12;
			int bankMask = machine.getComponentSelector().getMask();
			if (select == 15 && (bankMask & (1 << 4)) == 0) {
				if (BitComputersConfig.debugEEPROMWrites)
					BitComputers.log.info(String.format("[Bus] EEPROM Write $%04X = 0x%02X", address, data));
				machine.getEEPROM().write(address - EEPROM_DATA_BASE, data); // This reads eeprom code but offset must be data
			} else if (select >= 10 && select <= 13 && (bankMask & (1 << (select - 10))) == 0) {
				if (BitComputersConfig.debugComponentWrites)
					BitComputers.log.info(String.format("[Bus] Component Write $%04X = 0x%02X", address, data));
				int index = address >>> 8;
				index = (0xD0 - (index & 0xF0)) | (index & 0x0F);
				IBitComputersDevice device = machine.getComponentSelector().getComponent(index);
				if (device != null)
					device.write(machine.getContext(), address & 0xFF, data);
			} else {
				if (BitComputersConfig.debugMemoryWrites)
					BitComputers.log.info(String.format("[Bus] Memory Write $%04X = 0x%02X", address, data));
				BankSelector banksel = machine.getBankSelector();
				int memaddr = (banksel.bankSelect[select] << 12) | (address & 0xFFF);
				if (memaddr < machine.getMemsize())
					machine.writeMem(memaddr, (byte) data);
			}
			return;
		}
		if (BitComputersConfig.debugDeviceWrites)
			BitComputers.log.info(String.format("[Bus] Device Write $%04X = 0x%02X", address, data));
		for (Device device : this.deviceList) {
			MemoryRange memoryRange = device.getMemoryRange();
			if (address >= memoryRange.startAddress && address <= memoryRange.endAddress) {
				device.write(address - memoryRange.startAddress, data);
				return;
			}
		}
	}

    public void step() {
        for (Device device : this.deviceList) {
            device.step();
        }
    }

	public void onSignal(Signal signal) {
		for (Device device : deviceList)
			device.onSignal(signal);
	}

	public void load(NBTTagCompound nbt) {
		for (Device device : deviceList)
			device.load(nbt);
	}

	public void save(NBTTagCompound nbt) {
		for (Device device : deviceList)
			device.save(nbt);
	}

	public void assertIrq() {
		machine.getCpu().assertIrq();
	}

	public void clearIrq() {
		machine.getCpu().clearIrq();
	}

	public void assertNmi() {
		machine.getCpu().assertNmi();
	}

	public void clearNmi() {
		machine.getCpu().clearNmi();
	}

	public BitComputersMachine getMachine() {
		return machine;
	}

	public void setMachine(BitComputersMachine machine) {
		this.machine = machine;
	}

	public void setCpu(CPU cpu) {
		cpu.setBus(this);
	}

	public void addDevice(Device device) {
		deviceList.add(device);
		device.setBus(this);
	}
}
