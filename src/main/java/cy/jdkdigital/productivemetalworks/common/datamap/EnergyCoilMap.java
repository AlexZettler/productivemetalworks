package cy.jdkdigital.productivemetalworks.common.datamap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record EnergyCoilMap(int temperature, float consumption, float speed) {
    public static final Codec<EnergyCoilMap> CODEC = RecordCodecBuilder.create(builder -> builder.group(
                    Codec.INT.fieldOf("temperature").forGetter(EnergyCoilMap::temperature),
                    Codec.FLOAT.fieldOf("consumption").forGetter(EnergyCoilMap::consumption),
                    Codec.FLOAT.fieldOf("speed").forGetter(EnergyCoilMap::speed)
            )
            .apply(builder, EnergyCoilMap::new));
}
