package de.bookwaves;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration class representing an RFID reader.
 */
public class ReaderConfig {
    private String name;
    private String address;
    private int port;
    private String mode;
    private List<Integer> antennas = new ArrayList<>();

    public ReaderConfig() {
        // Default constructor for YAML deserialization
    }

    public ReaderConfig(String name, String address, int port, String mode, List<Integer> antennas) {
        this.name = name;
        this.address = address;
        this.port = port;
        this.mode = mode;
        this.antennas = antennas;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public List<Integer> getAntennas() {
        return antennas;
    }

    public void setAntennas(List<Integer> antennas) {
        this.antennas = antennas;
    }

    /**
     * Computes the antenna bitmask from the list of antenna numbers.
     * Antenna 1 = 0x01, Antenna 2 = 0x02, Antenna 3 = 0x04, etc.
     * Multiple antennas are combined with bitwise OR.
     * 
     * @return the antenna bitmask as a byte
     */
    public byte getAntennaMask() {
        int mask = 0;
        for (int antenna : antennas) {
            if (antenna >= 1 && antenna <= 8) {
                mask |= (1 << (antenna - 1));
            }
        }
        return (byte) mask;
    }
}
