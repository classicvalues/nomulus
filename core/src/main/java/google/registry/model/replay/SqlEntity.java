// Copyright 2020 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.replay;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.model.annotations.DeleteAfterMigration;
import java.util.Optional;

/**
 * An object that can be stored in Cloud SQL using {@link
 * javax.persistence.EntityManager#persist(Object)}
 *
 * <p>This will be used when replaying SQL transactions into Datastore, during the second,
 * SQL-primary, phase of the migration from Datastore to SQL.
 */
@DeleteAfterMigration
public interface SqlEntity {

  Optional<DatastoreEntity> toDatastoreEntity();

  /** A method that will be called before the object is saved to SQL in asynchronous replay. */
  default void beforeSqlSaveOnReplay() {}

  /** Returns this entity's primary key field(s) in a string. */
  default String getPrimaryKeyString() {
    return jpaTm()
        .transact(
            () ->
                String.format(
                    "%s_%s",
                    this.getClass().getSimpleName(),
                    jpaTm()
                        .getEntityManager()
                        .getEntityManagerFactory()
                        .getPersistenceUnitUtil()
                        .getIdentifier(this)));
  }
}
