package net.berrycompany.bitcomputers.devices.wdc;

import com.loomcom.symon.devices.Device;
import li.cil.oc.api.machine.Signal;
import net.berrycompany.bitcomputers.BitComputers;
import net.minecraft.nbt.NBTTagCompound;

/**
 * WDC W65C51N - ACIA (UART) emulation adapted to your Bus implementation.
 * <p>
 * - Uses getBus().assertIrq() / getBus().clearIrq()
 * - No assumptions about bus having serial methods.
 * - External code should attach a SerialSink to capture transmitted bytes.
 * - External code injects incoming bytes with receiveByte(int).
 * <p>
 * Timing: call step() once per emulator tick (Bus.step() already calls device.step()).
 */
@SuppressWarnings("unused")
public class ACIA extends Device {

    // Registers offsets within a 4-byte window
    private static final int REG_DATA = 0x0;
    private static final int REG_STATUS_OR_COMMAND = 0x1; // read=status, write=command
    private static final int REG_CONTROL = 0x2;
    private static final int REG_TEST = 0x3;

    /* Status bits (read) */
    private static final int ST_RDRF = 0x01; // Receiver Data Register Full
    private static final int ST_TDRE = 0x02; // Transmit Data Register Empty
    private static final int ST_DCD  = 0x04; // Carrier Detect (set = active)
    private static final int ST_CTS  = 0x08; // Clear To Send (set = active)
    private static final int ST_FE   = 0x10; // Framing Error
    private static final int ST_OVR  = 0x20; // Overrun Error
    private static final int ST_PE   = 0x40; // Parity Error
    private static final int ST_IRQ  = 0x80; // IRQ asserted

    /* Command bits (write) - simplified naming */
    private static final int CMD_RX_ENABLE     = 0x01; // receiver enable
    private static final int CMD_DTR_ENABLE    = 0x02; // DTR (not used)
    private static final int CMD_TX_IRQ_ENABLE = 0x04; // TX interrupt enable
    private static final int CMD_RX_IRQ_ENABLE = 0x08; // RX interrupt enable
    private static final int CMD_RTS_LOW       = 0x20; // RTS state (not used directly)
    private static final int CMD_TX_ENABLE     = 0x10; // transmitter enable

    // Simple baud table indices (control register encoding is implementation-dependent)
    // For practical purposes we'll map control -> baud in configureControl()
    // and allow a default of 9600 if unknown.
    private static final int DEFAULT_BAUD = 9600;

    // Machine-visible registers/state
    private int status = ST_TDRE | ST_CTS | ST_DCD; // default: TX empty, CTS/DCD active
    private int command = 0x00; // written to REG_STATUS_OR_COMMAND (write)
    private int control = 0x00; // configuration & baud selector
    private int test = 0x00;

    // Transmit / Receive holding registers
    private int rxHolding;
    private int txHolding;
    // shift state for bit-level emulation (optional detailed mode)
    //private int txShiftValue = 0;
    //private int txShiftBitsRemaining = 0;

    // Simple timing counters (decremented by step())
    private int txTickCounter = 0;
    private int rxTickCounter = 0;

    // Frame configuration
    private int dataBits = 8;   // 5..8
    private int stopBits = 1;   // 1 or 2
    private boolean parityEnabled = false;
    private boolean parityEven = true;

    // Effective baud (ticks per byte are cpuClock / baud). cpuClock must be known by caller or left as default.
    private int baud = DEFAULT_BAUD;
    private int cpuClockHz = 1000000; // default CPU clock used to convert baud -> ticks; override with setter if desired.

    // Serial output sink (attach your console, terminal, modem, or connect two ACIA's by providing a sink)
    public interface SerialSink {
        void onByteTransmitted(int b);
    }
    private SerialSink serialSink = null;

    // Constructor: baseAddress is the start address of the 4-byte window
    public ACIA(int startAddress) {
        super(startAddress, 4, "W65C51N ACIA");
    }

    // Allow emulator to set CPU clock used for baud -> tick conversion
    public void setCpuClockHz(int cpuClockHz) {
        if (cpuClockHz > 0) this.cpuClockHz = cpuClockHz;
    }

    // Attach a SerialSink to receive transmitted bytes
    public void setSerialSink(SerialSink sink) {
        this.serialSink = sink;
    }

    // External code uses this to inject received bytes (i.e., data arriving from wire/terminal)
    public void receiveByte(int b) {
        b &= 0xFF;
        // If RX not enabled, ignore input
        if ((this.command & CMD_RX_ENABLE) == 0) return;

        if (this.rxHolding != 0) {
            // overrun
            this.status |= ST_OVR;
        } else {
            this.rxHolding = b;
            this.status |= ST_RDRF;
        }

        this.maybeRaiseIrq();
    }

    /* ----------------------
       Device read/write API
       ---------------------- */
    @Override
    public int read(int address) {
        int reg = address & 0x03;
        switch (reg) {
            case REG_DATA:
                return this.readDataRegister();
            case REG_STATUS_OR_COMMAND:
                return this.readStatusRegister();
            case REG_CONTROL:
                return this.control & 0xFF;
            case REG_TEST:
                return this.test & 0xFF;
        }
        return 0;
    }

    private int readDataRegister() {
        if (this.rxHolding != 0) {
            int v = this.rxHolding & 0xFF;
            this.rxHolding = 0;
            // clear RDRF and related error bits on read
            this.status &= ~(ST_RDRF | ST_PE | ST_FE);
            this.clearIrqIfNoCause();
            return v;
        } else {
            return 0;
        }
    }

    private int readStatusRegister() {
        return status & 0xFF;
    }

    @Override
    public void write(int address, int data) {
        data &= 0xFF;
        int reg = address & 0x03;
        switch (reg) {
            case REG_DATA:
                this.writeDataRegister(data);
                break;
            case REG_STATUS_OR_COMMAND:
                this.writeCommandRegister(data);
                break;
            case REG_CONTROL:
                this.writeControlRegister(data);
                break;
            case REG_TEST:
                this.test = data;
                break;
        }
    }

    private void writeDataRegister(int data) {
        if ((this.command & CMD_TX_ENABLE) != 0) {
            // If TX holding already contains data, we overwrite/ignore (real ACIA behavior uses single byte holding).
            this.txHolding = data & 0xFF;
            this.status &= ~ST_TDRE;
            // prepare shift register/frame for transmit in step()
            this.loadTxShiftRegister();
        } else if ((this.command & CMD_RX_ENABLE) != 0) {
            // If RX holding already contains data, we overwrite/ignore (real ACIA behavior uses single byte holding).
            this.rxHolding = data & 0xFF;
            this.status &= ~ST_RDRF;
            // prepare shift register/frame for transmit in step()
            this.loadRxShiftRegister();
        }
    }

    private void writeCommandRegister(int data) {
        this.command = data & 0xFF;
        // some bits (e.g. clear IRQs) are side-effectful; we respond by checking interrupts
        this.maybeRaiseIrq();
    }

    private void writeControlRegister(int data) {
        this.control = data & 0xFF;
        this.configureFromControl();
    }

    /* ----------------------
       Configuration helpers
       ---------------------- */
    private void configureFromControl() {
        // control register encoding varies by implementation.
        // We'll use a pragmatic mapping:
        // lower 3 bits -> select a baud divisor preset; map to common rates (0..7)
        // bits 0..2: baud index 0..7
        // bit 3: hi-bank select (0 => bank0, 1 => bank1) to extend choices
        // bits 4..6 -> format: we'll decode a few common formats
        int baudIndex = this.control & 0x07;
        int hiBank = (this.control >> 3) & 0x01;
        this.baud = this.pickBaud(hiBank, baudIndex);

        int format = (control >> 4) & 0x07;
        switch (format) {
            case 0:
                this.dataBits = 8; this.stopBits = 1; this.parityEnabled = false; break;
            case 1:
                this.dataBits = 7; this.stopBits = 1; this.parityEnabled = false; break;
            case 2:
                this.dataBits = 7; this.stopBits = 1; this.parityEnabled = true; this.parityEven = true; break;
            case 3:
                this.dataBits = 7; this.stopBits = 1; this.parityEnabled = true; this.parityEven = false; break;
            case 4:
                this.dataBits = 8; this.stopBits = 2; this.parityEnabled = false; break;
            default:
                this.dataBits = 8; this.stopBits = 1; this.parityEnabled = false; break;
        }
    }

    private int pickBaud(int bank, int idx) {
        // a simple table covering common rates
        final int[][] tbl = {
                {50, 75, 110, 134, 150, 300, 600, 1200},
                {1800, 2400, 3600, 4800, 7200, 9600, 19200, 38400}
        };
        if (bank < 0 || bank >= tbl.length || idx < 0 || idx >= tbl[0].length) return DEFAULT_BAUD;
        return tbl[bank][idx];
    }

    private int computeTicksPerByte() {
        // convert baud -> emulator ticks per byte. assume cpuClockHz ticks per second.
        if (this.baud <= 0) {
            return 1;
        } else {
            int bitsInFrame = 1 + this.dataBits + (this.parityEnabled ? 1 : 0) + (this.parityEven ? 1 : 0) + this.stopBits; // start + data + parity + stop
            // ticks per bit = cpuClockHz / baud; ticks per frame = bitsInFrame * ticksPerBit
            long ticksPerBit = Math.max(1, this.cpuClockHz / this.baud);
            long ticksPerFrame = ticksPerBit * Math.max(1, bitsInFrame);
            if (ticksPerFrame > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) ticksPerFrame;
        }
    }

    /* ----------------------
       Transmit framing/shift
       ---------------------- */
    private void loadTxShiftRegister() {
        if (!(this.txHolding == 0)) {
            // For simplicity we do not perform bit-level parity calculation / bit-by-bit emission in perfect detail.
            // We'll instead wait for txTickCounter to elapse and then atomically transmit the byte.
            this.txTickCounter = this.computeTicksPerByte();
        }
    }

    private void loadRxShiftRegister() {
        if (!(this.rxHolding == 0)) {
            // For simplicity we do not perform bit-level parity calculation / bit-by-bit emission in perfect detail.
            // We'll instead wait for txTickCounter to elapse and then atomically transmit the byte.
            this.rxTickCounter = this.computeTicksPerByte();
        }
    }

    /* ----------------------
       IRQ handling helpers
       ---------------------- */
    private boolean interruptWanted() {
        boolean rxInt = ((this.command & CMD_RX_IRQ_ENABLE) != 0) && ((this.status & ST_RDRF) != 0);
        boolean txInt = ((this.command & CMD_TX_IRQ_ENABLE) != 0) && ((this.status & ST_TDRE) != 0);
        return rxInt || txInt;
    }

    private void maybeRaiseIrq() {
        if (interruptWanted()) {
            this.status |= ST_IRQ;
            this.getBus().assertIrq();
        } else {
            // if nothing wants IRQ, clear.
            this.status &= ~ST_IRQ;
            this.getBus().clearIrq();
        }
    }

    private void clearIrqIfNoCause() {
        if (!interruptWanted()) {
            this.status &= ~ST_IRQ;
            this.getBus().clearIrq();
        }
    }

    /* ----------------------
       Step: called each emulator tick by Bus.step()
       ---------------------- */
    public void step() {
        // Transmit machinery:
        if (this.txHolding != 0) {
            if (--this.txTickCounter <= 0) {
                // "transmit" the byte
                int b = this.txHolding & 0xFF;
                if (this.serialSink != null) {
                    try {
                        this.serialSink.onByteTransmitted(b);
                    } catch (Throwable t) {
                        // Protect emulator from listener exceptions
                        BitComputers.log.warn(t.getMessage(), t);
                    }
                }
                // mark transmit register empty
                this.txHolding = 0;
                this.status |= ST_TDRE;
                // maybe notify IRQ if TX IRQ enabled
                this.maybeRaiseIrq();
            }
        }

        // RX timing would be driven by external source calling receiveByte()
        // We keep rxTickCounter logic reserved if per-bit emulation is later implemented.
    }

    /* ----------------------
       onSignal / save / load
       ---------------------- */
    @Override
    public void onSignal(Signal signal) {
        if (signal == null || signal.name() == null) return;

        // Normalize the signal name
        String name = signal.name().trim().toUpperCase();

        // Standard device reset names commonly used in 6502 emulators
        switch (name) {
            case "RESET":
            case "RES":
            case "RST":
                this.resetDevice();
                break;

            case "NMI":
            case "NONMASKABLEINTERRUPT":
            case "NON_MASKABLE_INTERRUPT":
                // ACIA doesn't have explicit NMI behavior — ignore or log if needed
                this.getBus().assertNmi();
                break;

            case "IRQ":
                // CPU-driven IRQ line input — ACIA usually *drives* IRQ not receives it
                this.getBus().assertIrq();
                break;

            default:
                // Unknown signal: ignore or add logging if debugging
                break;
        }
    }

    private void resetDevice() {
        this.status = ST_TDRE | ST_CTS | ST_DCD; // TX empty, CTS/DCD active
        this.command = 0;
        this.control = 0;
        this.test = 0;
        this.rxHolding = 0;
        this.txHolding = 0;
        this.txTickCounter = 0;
        this.rxTickCounter = 0;

        // Clear interrupts via bus
        this.getBus().clearIrq();
    }

    @Override
    public void save(NBTTagCompound nbt) {
        nbt.setInteger(getName() + "_status", this.status);
        nbt.setInteger(getName() + "_command", this.command);
        nbt.setInteger(getName() + "_control", this.control);
        nbt.setInteger(getName() + "_test", this.test);
        nbt.setInteger(getName() + "_txHolding", this.txHolding);
        nbt.setInteger(getName() + "_rxHolding", this.rxHolding);
        nbt.setInteger(getName() + "_baud", this.baud);
        nbt.setInteger(getName() + "_cpuClockHz", this.cpuClockHz);
    }

    @Override
    public void load(NBTTagCompound nbt) {
        this. status = nbt.getInteger(getName() + "_status");
        this.command = nbt.getInteger(getName() + "_command");
        this.control = nbt.getInteger(getName() + "_control");
        this.test = nbt.getInteger(getName() + "_test");
        int txHolding = nbt.getInteger(getName() + "_txHolding");
        int rxHolding = nbt.getInteger(getName() + "_rxHolding");
        this.baud = nbt.getInteger(getName() + "_baud");
        this.cpuClockHz = nbt.getInteger(getName() + "_cpuClockHz");
        // Re-evaluate IRQ state
        this.maybeRaiseIrq();
    }

    /* ----------------------
       Utility / debugging
       ---------------------- */
    @Override
    public String toString() {
        return String.format("%s (W65C51N)@%04X", getName(), startAddress());
    }
}
