package net.berrycompany.bitcomputers.devices.wdc;

import com.loomcom.symon.devices.Device;
import li.cil.oc.api.machine.Signal;
import net.minecraft.nbt.NBTTagCompound;

@SuppressWarnings("unused")
public class PIA extends Device {

    // Register offsets
    private static final int REG_PORTB = 0x0;
    private static final int REG_PORTA = 0x1;
    private static final int REG_CONTROL_B = 0x2;
    private static final int REG_CONTROL_A = 0x3;

    // Internal port registers
    private int portA = 0x00;
    private int portB = 0x00;

    // Data direction: 1=output, 0=input
    private int ddra = 0x00;
    private int ddrb = 0x00;

    // Control registers
    private int controlA = 0x00;
    private int controlB = 0x00;

    // Control lines (true=high, false=low)
    private boolean ca1 = false;
    private boolean ca2 = false;
    private boolean cb1 = false;
    private boolean cb2 = false;

    // IRQ flags
    private boolean irqA = false;
    private boolean irqB = false;

    // CA2/CB2 handshake output
    private boolean ca2Output = false;
    private boolean cb2Output = false;

    public PIA(int startAddress) {
        super(startAddress, 4, "W65C21 PIA");
    }

    /* ---------------------------
       Read/Write interface
       --------------------------- */
    @Override
    public int read(int address) {
        int reg = address & 0x03;
        switch (reg) {
            case REG_PORTB: return readPortB();
            case REG_PORTA: return readPortA();
            case REG_CONTROL_B: return readControlB();
            case REG_CONTROL_A: return readControlA();
        }
        return 0;
    }

    @Override
    public void write(int address, int data) {
        int reg = address & 0x03;
        data &= 0xFF;
        switch (reg) {
            case REG_PORTB: writePortB(data); break;
            case REG_PORTA: writePortA(data); break;
            case REG_CONTROL_B: writeControlB(data); break;
            case REG_CONTROL_A: writeControlA(data); break;
        }
    }

    /* ---------------------------
       Port A
       --------------------------- */
    private int readPortA() {
        // Combine output and external input based on DDR
        int input = portA & ~ddra; // external input bits
        int output = portA & ddra; // last written output bits
        return (input | output) & 0xFF;
    }

    private void writePortA(int data) {
        portA = data & 0xFF;
        updateCA2Output();
    }

    /* ---------------------------
       Port B
       --------------------------- */
    private int readPortB() {
        int input = portB & ~ddrb;
        int output = portB & ddrb;
        return (input | output) & 0xFF;
    }

    private void writePortB(int data) {
        portB = data & 0xFF;
        updateCB2Output();
    }

    /* ---------------------------
       Control register handling
       --------------------------- */

    private int readControlA() {
        int status = 0;
        if (ca1) status |= 0x01; // CA1 input
        if (ca2) status |= 0x02; // CA2 input
        if (irqA) status |= 0x80; // IRQ flag
        return status;
    }

    private int readControlB() {
        int status = 0;
        if (cb1) status |= 0x01; // CB1 input
        if (cb2) status |= 0x02; // CB2 input
        if (irqB) status |= 0x80; // IRQ flag
        return status;
    }

    private void writeControlA(int data) {
        controlA = data & 0xFF;
        ddra = (data & 0x0F);       // lower 4 bits = DDR A
        updateCA2Output();
        maybeRaiseIrq();
    }

    private void writeControlB(int data) {
        controlB = data & 0xFF;
        ddrb = (data & 0x0F);       // lower 4 bits = DDR B
        updateCB2Output();
        maybeRaiseIrq();
    }

    /* ---------------------------
   CA2/CB2 handshake
   --------------------------- */
    private void updateCA2Output() {
        // Simple output: if configured as output, mirror bit 1
        ca2Output = ((controlA & 0x02) != 0) && ((portA & 0x02) != 0);
    }

    private void updateCB2Output() {
        cb2Output = ((controlB & 0x02) != 0) && ((portB & 0x02) != 0);
    }

    /* ---------------------------
       External control line setters
       --------------------------- */
    public void setCA1(boolean level) {
        if (ca1 != level) {
            ca1 = level;
            irqA = ((controlA & 0x10) != 0); // interrupt enabled
            maybeRaiseIrq();
        }
    }

    public void setCA2(boolean level) {
        if ((controlA & 0x02) == 0) { // input mode
            if (ca2 != level) {
                ca2 = level;
                irqA = ((controlA & 0x20) != 0);
                maybeRaiseIrq();
            }
        }
    }

    public void setCB1(boolean level) {
        if (this.cb1 != level) {
            this.cb1 = level;
            this.irqB = ((controlB & 0x10) != 0);
            this.maybeRaiseIrq();
        }
    }

    public void setCB2(boolean level) {
        if ((this.controlB & 0x02) == 0) { // input mode
            if (this.cb2 != level) {
                this.cb2 = level;
                this.irqB = ((controlB & 0x20) != 0);
                this.maybeRaiseIrq();
            }
        }
    }

    /* ---------------------------
       IRQ management
       --------------------------- */
    private void maybeRaiseIrq() {
        if (this.irqA || this.irqB) {
            this.getBus().assertIrq();
        } else {
            this.getBus().clearIrq();
        }
    }

    @Override
    public void step() {
        // No timing-specific behavior in basic PIA emulation
    }

    @Override
    public void onSignal(Signal signal) {
        if (signal != null && signal.name() != null) {
            String name = signal.name().trim().toUpperCase();
            if (name.equals("RESET") || name.equals("RES") || name.equals("RST")) {
                this.resetDevice();
            }
        }
    }

    private void resetDevice() {
        this.portA = this.portB = 0;
        this.ddra = this.ddrb = 0;
        this.controlA = this.controlB = 0;
        this.ca1 = this.ca2 = this.cb1 = this.cb2 = false;
        this.ca2Output = this.cb2Output = false;
        this.irqA = this.irqB = false;
        this.getBus().clearIrq();
    }

    /* ---------------------------
       Save/Load state
       --------------------------- */
    @Override
    public void save(NBTTagCompound nbt) {
        nbt.setInteger(getName() + "_portA", this.portA);
        nbt.setInteger(getName() + "_portB", this.portB);
        nbt.setInteger(getName() + "_ddra", this.ddra);
        nbt.setInteger(getName() + "_ddrb", this.ddrb);
        nbt.setInteger(getName() + "_controlA", this.controlA);
        nbt.setInteger(getName() + "_controlB", this.controlB);
        nbt.setInteger(getName() + "_ca1", this.ca1 ? 1 : 0);
        nbt.setInteger(getName() + "_ca2", this.ca2 ? 1 : 0);
        nbt.setInteger(getName() + "_cb1", this.cb1 ? 1 : 0);
        nbt.setInteger(getName() + "_cb2", this.cb2 ? 1 : 0);
        nbt.setInteger(getName() + "_irqA", this.irqA ? 1 : 0);
        nbt.setInteger(getName() + "_irqB", this.irqB ? 1 : 0);
        nbt.setInteger(getName() + "_ca2Out", this.ca2Output ? 1 : 0);
        nbt.setInteger(getName() + "_cb2Out", this.cb2Output ? 1 : 0);
    }

    @Override
    public void load(NBTTagCompound nbt) {
        this.portA = nbt.getInteger(getName() + "_portA");
        this.portB = nbt.getInteger(getName() + "_portB");
        this.ddra = nbt.getInteger(getName() + "_ddra");
        this.ddrb = nbt.getInteger(getName() + "_ddrb");
        this.controlA = nbt.getInteger(getName() + "_controlA");
        this.controlB = nbt.getInteger(getName() + "_controlB");
        this.ca1 = nbt.getInteger(getName() + "_ca1") != 0;
        this.ca2 = nbt.getInteger(getName() + "_ca2") != 0;
        this.cb1 = nbt.getInteger(getName() + "_cb1") != 0;
        this.cb2 = nbt.getInteger(getName() + "_cb2") != 0;
        this.irqA = nbt.getInteger(getName() + "_irqA") != 0;
        this.irqB = nbt.getInteger(getName() + "_irqB") != 0;
        this.ca2Output = nbt.getInteger(getName() + "_ca2Out") != 0;
        this.cb2Output = nbt.getInteger(getName() + "_cb2Out") != 0;
        this.maybeRaiseIrq();
    }

    @Override
    public String toString() {
        return String.format("%s (W65C21 PIA)@%04X", getName(), startAddress());
    }
}
