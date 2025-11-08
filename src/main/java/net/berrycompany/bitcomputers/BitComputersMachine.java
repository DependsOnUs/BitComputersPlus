package net.berrycompany.bitcomputers;

import com.loomcom.symon.Bus;
import com.loomcom.symon.CPU;
import li.cil.oc.api.machine.Context;
import net.berrycompany.bitcomputers.devices.opencomputers.*;

import java.util.ArrayList;

public abstract class BitComputersMachine {
    // The machine memory
    private final ArrayList<Byte> mem = new ArrayList<>();

    public abstract Bus getBus();

    public abstract CPU getCpu();

    public abstract GeneralIO getGioDev();

    public abstract ComponentSelector getComponentSelector();

    public abstract BankSelector getBankSelector();

    public abstract CopyEngine getCopyEngine();

    public abstract RTC getRTC();

    public abstract ComputerInfo getCompInfo();

    public abstract BootROM getEEPROM();

    public abstract String getName();

    public abstract Context getContext();

    public void resize(int newsize) {
        if (newsize > this.mem.size())
            for (int i = this.mem.size(); i < newsize; i++)
                this.mem.add((byte) 0xFF);
        else if (newsize < this.mem.size())
            for (int i = this.mem.size(); i > newsize; i--)
                this.mem.remove(i - 1);
    }

    public abstract void reset();

    public int getMemsize() {
        return this.mem.size();
    }

    public byte readMem(int address) {
        return this.mem.get(address);
    }

    public void writeMem(int address, byte data) {
        this.mem.set(address, data);
    }
}
