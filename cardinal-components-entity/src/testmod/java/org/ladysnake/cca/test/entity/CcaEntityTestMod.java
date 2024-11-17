/*
 * Cardinal-Components-API
 * Copyright (C) 2019-2024 Ladysnake
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.ladysnake.cca.test.entity;

import net.fabricmc.api.ModInitializer;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.passive.CamelEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import org.ladysnake.cca.api.v3.entity.EntityComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.entity.EntityComponentInitializer;
import org.ladysnake.cca.api.v3.entity.RespawnCopyStrategy;
import org.ladysnake.cca.test.base.BaseVita;
import org.ladysnake.cca.test.base.Energy;
import org.ladysnake.cca.test.base.LoadAwareTestComponent;
import org.ladysnake.cca.test.base.Vita;

public class CcaEntityTestMod implements ModInitializer, EntityComponentInitializer {
    public static final RegistryKey<EntityType<?>> TEST_ENTITY_ID = RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of("cca-entity-test", "test"));
    public static final EntityType<TestEntity> TEST_ENTITY = EntityType.Builder.create(TestEntity::new, SpawnGroup.MISC).build(TEST_ENTITY_ID);
    public static final int NATURAL_VITA_CEILING = 10;
    public static final int CAMEL_BASE_VITA = 50;

    public static BaseVita createForEntity(LivingEntity e) {
        return new BaseVita((int) (Math.random() * NATURAL_VITA_CEILING));
    }

    @Override
    public void registerEntityComponentFactories(EntityComponentFactoryRegistry registry) {
        registry.registerFor(LivingEntity.class, Vita.KEY, CcaEntityTestMod::createForEntity);
        registry.beginRegistration(PlayerEntity.class, Vita.KEY).impl(PlayerVita.class).end(PlayerVita::new);
        registry.beginRegistration(CamelEntity.class, Vita.KEY).impl(EntityVita.class).respawnStrategy(RespawnCopyStrategy.ALWAYS_COPY).end(owner -> new EntityVita(owner, CAMEL_BASE_VITA));
        registry.beginRegistration(ShulkerEntity.class, LoadAwareTestComponent.KEY).impl(LoadAwareTestComponent.class).end(e -> new LoadAwareTestComponent());
        registry.beginImmutableRegistration(PlayerEntity.class, Energy.KEY)
            /*.onServerTick(PlayerEnergy::onServerTick)
            .onClientTick(PlayerEnergy::onClientTick)
            .onServerLoad(PlayerEnergy::onServerLoad)
            .onClientLoad(PlayerEnergy::onClientLoad)*/
            .respawnStrategy(RespawnCopyStrategy.INVENTORY)
            .end($ -> new Energy(0));
    }

    @Override
    public void onInitialize() {
        Registry.register(Registries.ENTITY_TYPE, TEST_ENTITY_ID, TEST_ENTITY);
    }
}
