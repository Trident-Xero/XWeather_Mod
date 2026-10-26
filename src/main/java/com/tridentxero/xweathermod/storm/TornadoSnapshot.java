package com.tridentxero.xweathermod.storm;

/** Compact client view of one tornado. Velocity is blocks per tick; size is the radius in blocks. */
public record TornadoSnapshot(int id, float x, float z, float vx, float vz, float windMph, float size, float intensity) {
}
