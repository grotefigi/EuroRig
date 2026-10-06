package org.eurorig.routing;

/** Actual loaded dimensions and weight, in metres and tonnes. */
public final class Truck {
    public final double height, width, length, weight, axleWeight;
    public final boolean hazmat, avoidTolls, avoidFerries, avoidUnpaved;
    public Truck(double height, double width, double length, double weight, double axleWeight,
                 boolean hazmat, boolean avoidTolls, boolean avoidFerries, boolean avoidUnpaved) {
        double[] values = {height, width, length, weight, axleWeight};
        double[] maxima = {10, 10, 50, 200, 40};
        for (int i = 0; i < values.length; i++)
            if (!Double.isFinite(values[i]) || values[i] <= 0 || values[i] > maxima[i])
                throw new IllegalArgumentException("Enter valid positive truck dimensions and loaded weights.");
        if (axleWeight > weight) throw new IllegalArgumentException("Axle weight cannot exceed gross weight.");
        this.height = height; this.width = width; this.length = length; this.weight = weight;
        this.axleWeight = axleWeight; this.hazmat = hazmat; this.avoidTolls = avoidTolls;
        this.avoidFerries = avoidFerries; this.avoidUnpaved = avoidUnpaved;
    }
    public static Truck standard() { return new Truck(4, 2.55, 16.5, 40, 11.5, false, false, true, true); }
}
