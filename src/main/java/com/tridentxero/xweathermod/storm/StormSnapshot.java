package com.tridentxero.xweathermod.storm;

/**
 * Compact, immutable view of one storm as sent to clients.
 * Velocity is in blocks per tick so clients can extrapolate between syncs.
 * supercell: drawn with a supercell structure (updraft tower, anvil, wall cloud).
 * development: 0..1, how far the supercell has built toward its tornado; 1 for a mature one (always 0 if not a supercell).
 */
public record StormSnapshot(int id, float x, float z, float vx, float vz, float radius, float intensity,
                            boolean supercell, float development) {
}
