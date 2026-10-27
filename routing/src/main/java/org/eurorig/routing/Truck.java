package org.eurorig.routing;

/** Actual loaded dimensions and weight, in metres and tonnes. */
public final class Truck {
    public final double height, width, length, weight, axleWeight;
    /** Documented permitted maximum mass. Informational only: routing always uses the loaded mass. */
    public final double permittedWeight;
    public final boolean hazmat, avoidTolls, avoidFerries, avoidUnpaved;
    public final int axles, hazardousLoad, tunnelCode;
    public final double topSpeed;
    public Truck(double height, double width, double length, double weight, double axleWeight,
                 boolean hazmat, boolean avoidTolls, boolean avoidFerries, boolean avoidUnpaved) {
        this(height,width,length,weight,axleWeight,hazmat,avoidTolls,avoidFerries,avoidUnpaved,5,80,hazmat?1:0,0);
    }
    public Truck(double height,double width,double length,double weight,double axleWeight,
                 boolean hazmat,boolean avoidTolls,boolean avoidFerries,boolean avoidUnpaved,
                 int axles,double topSpeed,int hazardousLoad,int tunnelCode){
        this(height,width,length,weight,axleWeight,hazmat,avoidTolls,avoidFerries,avoidUnpaved,
             axles,topSpeed,hazardousLoad,tunnelCode,weight);
    }
    public Truck(double height,double width,double length,double weight,double axleWeight,
                 boolean hazmat,boolean avoidTolls,boolean avoidFerries,boolean avoidUnpaved,
                 int axles,double topSpeed,int hazardousLoad,int tunnelCode,double permittedWeight){
        double[] values = {height, width, length, weight, axleWeight};
        double[] maxima = {10, 10, 50, 200, 40};
        for (int i = 0; i < values.length; i++)
            if (!Double.isFinite(values[i]) || values[i] <= 0 || values[i] > maxima[i])
                throw new IllegalArgumentException("Enter valid positive truck dimensions and loaded weights.");
        if (axleWeight > weight) throw new IllegalArgumentException("Axle weight cannot exceed gross weight.");
        if (!Double.isFinite(permittedWeight) || permittedWeight <= 0 || permittedWeight > 200)
            throw new IllegalArgumentException("Enter the permitted maximum mass from the vehicle documents.");
        if(axles<2||axles>20||!Double.isFinite(topSpeed)||topSpeed<10||topSpeed>120||hazardousLoad<0||hazardousLoad>7||tunnelCode<0||tunnelCode>5||tunnelCode==1)
            throw new IllegalArgumentException("Check total axles, maximum speed, hazardous load and tunnel code.");
        this.axles=axles;this.topSpeed=topSpeed;this.hazardousLoad=hazardousLoad;this.tunnelCode=tunnelCode;
        this.permittedWeight = permittedWeight;
        this.height = height; this.width = width; this.length = length; this.weight = weight;
        this.axleWeight = axleWeight; this.hazmat = hazmat||hazardousLoad!=0||tunnelCode!=0; this.avoidTolls = avoidTolls;
        this.avoidFerries = avoidFerries; this.avoidUnpaved = avoidUnpaved;
    }
    /** Loaded mass exceeds the documented permitted mass: report it, never silently reconcile it. */
    public boolean exceedsPermittedMass(){return weight>permittedWeight+0.001;}
    public static Truck standard() { return new Truck(4, 2.55, 16.5, 40, 11.5, false, false, true, true); }
}
