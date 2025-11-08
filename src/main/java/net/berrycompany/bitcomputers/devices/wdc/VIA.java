package net.berrycompany.bitcomputers.devices.wdc;

import com.loomcom.symon.devices.Device;
import li.cil.oc.api.machine.Signal;
import net.minecraft.nbt.NBTTagCompound;

@SuppressWarnings("unused")
public class VIA extends Device {

    // Register offsets within the 16-byte window
    private static final int ORB_IRB = 0x0;
    private static final int ORA_IRA = 0x1;
    private static final int DDRB = 0x2;
    private static final int DDRA = 0x3;
    private static final int T1C_L = 0x4;
    private static final int T1C_H = 0x5;
    private static final int T1L_L = 0x6;
    private static final int T1L_H = 0x7;
    private static final int T2C_L = 0x8;
    private static final int T2C_H = 0x9;
    private static final int SR = 0xA;
    private static final int ACR = 0xB;
    private static final int PCR = 0xC;
    private static final int IFR = 0xD;
    private static final int IER = 0xE;
    private static final int ORA_NO_LATCH = 0xF;

    // IFR bits (subset used)
    private static final int IFR_CIF_TIMER1 = 1 << 6; // bit 6
    private static final int IFR_CIF_TIMER2 = 1 << 5; // bit 5

    // Registers
    private int regORB = 0x00;
    private int regORA = 0x00;
    private int regDDRB = 0x00;
    private int regDDRA = 0x00;
    private int timer1Counter = 0;
    private int timer1Latch = 0;
    private int timer2Counter = 0;
    private int timer2Latch = 0;
    private int regSR = 0x00;
    private int regACR = 0x00;
    private int regPCR = 0x00;
    private int regIFR = 0x00; // bits set to indicate source; bit7 is IRQ state upon read
    private int regIER = 0x00; // bit7 is mask indicator in read

    public VIA(int startAddress) {
        super(startAddress, 16, "W65C22 VIA");
    }

    @Override
    public int read(int address) {
        int reg = address & 0x0F;
        switch (reg) {
            case ORB_IRB:
                return readPort(this.regORB, this.regDDRB);
            case ORA_IRA:
            case ORA_NO_LATCH:
                return readPort(this.regORA, this.regDDRA);
            case DDRB:
                return this.regDDRB & 0xFF;
            case DDRA:
                return this.regDDRA & 0xFF;
            case T1C_L:
                this.clearIFR(IFR_CIF_TIMER1);
                return this.timer1Counter & 0xFF;
            case T1C_H:
                return (this.timer1Counter >> 8) & 0xFF;
            case T1L_L:
                return this.timer1Latch & 0xFF;
            case T1L_H:
                return (this.timer1Latch >> 8) & 0xFF;
            case T2C_L:
                this.clearIFR(IFR_CIF_TIMER2);
                return this.timer2Counter & 0xFF;
            case T2C_H:
                return (this.timer2Counter >> 8) & 0xFF;
            case SR:
                return this.regSR & 0xFF;
            case ACR:
                return this.regACR & 0xFF;
            case PCR:
                return this.regPCR & 0xFF;
            case IFR:
                int top = (this.interruptPending() ? 0x80 : 0x00);
                return (regIFR & 0x7F) | top;
            case IER:
                return (regIER & 0x7F) | 0x80; // bit7 is always 1 when read
        }
        return 0xFF;
    }

    @Override
    public void write(int address, int data) {
        int reg = address & 0x0F;
        data &= 0xFF;
        switch (reg) {
            case ORB_IRB:
                this.regORB = data;
                break;
            case ORA_IRA:
            case ORA_NO_LATCH:
                this.regORA = data;
                break;
            case DDRB:
                this.regDDRB = data;
                break;
            case DDRA:
                this.regDDRA = data;
                break;
            case T1C_L:
            case T1L_L:
                this.timer1Latch = (this.timer1Latch & 0xFF00) | data;
                break;
            case T1C_H:
                this.timer1Latch = (this.timer1Latch & 0x00FF) | (data << 8);
                this.timer1Counter = this.timer1Latch; // load into counter
                this.clearIFR(IFR_CIF_TIMER1);
                break;
            case T1L_H:
                this.timer1Latch = (this.timer1Latch & 0x00FF) | (data << 8);
                break;
            case T2C_L:
                this.timer2Latch = (this.timer2Latch & 0xFF00) | data;
                break;
            case T2C_H:
                this.timer2Latch = (this.timer2Latch & 0x00FF) | (data << 8);
                this.timer2Counter = this.timer2Latch;
                this.clearIFR(IFR_CIF_TIMER2);
                break;
            case SR:
                this.regSR = data;
                break;
            case ACR:
                this.regACR = data;
                break;
            case PCR:
                this.regPCR = data;
                break;
            case IFR:
                // writing ones clears the IFR bits (low 7 bits)
                this.regIFR &= ~(data & 0x7F);
                // if no more IFR & IER bits -> clear physical IRQ on bus
                this.updateBusIRQ();
                break;
            case IER:
                // bit7 = 1 means set bits, 0 means clear bits
                if ((data & 0x80) != 0) {
                    this.regIER |= (data & 0x7F);
                } else {
                    this.regIER &= ~(data & 0x7F);
                }

                this.updateBusIRQ();
                break;
        }
    }

    /** Called by emulator main loop every clock tick. */
    public void step() {
        // 16-bit timers: decrement and set IFR when reaching underflow
        if (this.timer1Counter > 0) {
            this.timer1Counter--;
            if (this.timer1Counter == 0xFFFF) { // underflow (since we used unsigned wrap)
                // set IFR bit
                this.regIFR |= IFR_CIF_TIMER1;
                this.updateBusIRQ();
                // For this simple model: reload latch (free-run behavior)
                this.timer1Counter = this.timer1Latch & 0xFFFF;
            }
        }

        if (this.timer2Counter > 0) {
            this.timer2Counter--;
            if (this.timer2Counter == 0xFFFF) {
                this.regIFR |= IFR_CIF_TIMER2;
                this.updateBusIRQ();
                this.timer2Counter = timer2Latch & 0xFFFF;
            }
        }
    }

    private int readPort(int latch, int ddr) {
        int inputMask = (~ddr) & 0xFF; // bits that are inputs = 1
        int inputs = this.getBus() != null ? this.getBus().read(inputMask) : inputMask;
        // If DDR bit=1 -> return latch bit; if DDR bit=0 -> return input bit
        return (latch & ddr) | (inputs & inputMask);
    }

    private void clearIFR(int mask) {
        this.regIFR &= ~mask;
        this.updateBusIRQ();
    }

    private boolean interruptPending() {
        return (this.regIFR & this.regIER & 0x7F) != 0;
    }

    private void updateBusIRQ() {
        if (this.interruptPending()) {
            this.getBus().assertIrq();
        } else {
            // This is a simplification: clearing the bus IRQ globally.
            // In a multi-device bus we'd need wire-OR semantics with counting.
            this.getBus().clearIrq();
        }
    }

    @Override
    public void onSignal(Signal signal) {
        if (signal != null && signal.name() != null) {
            String name = signal.name().trim().toUpperCase();
            if (name.equals("RESET") || name.equals("RES") || name.equals("RST")) {
                resetDevice();
            }
        }
    }

    public void resetDevice() {
        // Reset many registers to defaults
        this.regORB = this.regORA = 0x00;
        this.regDDRA = this.regDDRB = 0x00;
        this.regACR = this.regPCR = this.regIFR = this.regIER = 0x00;
        this.timer1Counter = this.timer1Latch = 0;
        this.timer2Counter = this.timer2Latch = 0;
        this.getBus().clearIrq();
    }

    @Override
    public void save(NBTTagCompound nbt) {
        nbt.setInteger("ORB", this.regORB);
        nbt.setInteger("ORA", this.regORA);
        nbt.setInteger("DDRB", this.regDDRB);
        nbt.setInteger("DDRA", this.regDDRA);
        nbt.setInteger("T1C", this.timer1Counter);
        nbt.setInteger("T1L", this.timer1Latch);
        nbt.setInteger("T2C", this.timer2Counter);
        nbt.setInteger("T2L", this.timer2Latch);
        nbt.setInteger("SR", this.regSR);
        nbt.setInteger("ACR", this.regACR);
        nbt.setInteger("PCR", this.regPCR);
        nbt.setInteger("IFR", this.regIFR);
        nbt.setInteger("IER", this.regIER);
    }

    @Override
    public void load(NBTTagCompound nbt) {
        this.regORB = nbt.getInteger("ORB");
        this.regORA = nbt.getInteger("ORA");
        this.regDDRB = nbt.getInteger("DDRB");
        this.regDDRA = nbt.getInteger("DDRA");
        this.timer1Counter = nbt.getInteger("T1C");
        this.timer1Latch = nbt.getInteger("T1L");
        this.timer2Counter = nbt.getInteger("T2C");
        this.timer2Latch = nbt.getInteger("T2L");
        this.regSR = nbt.getInteger("SR");
        this.regACR = nbt.getInteger("ACR");
        this.regPCR = nbt.getInteger("PCR");
        this.regIFR = nbt.getInteger("IFR");
        this.regIER = nbt.getInteger("IER");
        this.updateBusIRQ();
    }
}
