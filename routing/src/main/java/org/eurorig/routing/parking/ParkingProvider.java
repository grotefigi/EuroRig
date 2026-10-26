package org.eurorig.routing.parking;
import java.io.*;
/** Implementations translate public feeds; routing and freshness policy stay shared. */
public interface ParkingProvider {
    String id();String attribution();String tableUrl();String statusUrl();
    Parking.Table table(byte[] xml)throws IOException;
    Parking.Status status(byte[] xml)throws IOException;
}
