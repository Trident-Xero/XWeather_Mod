package com.tridentxero.xweathermod.storm;

/**
 * Compact, immutable view of one storm as sent to clients.
 * Velocity is in blocks per tick so clients can extrapolate between syncs.
 */
public record StormSnapshot(int id, float x, float z, float vx, float vz, float radius, float intensity) {
}
