// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.tmch;

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.model.common.CrossTldSingleton;
import google.registry.model.replay.SqlOnlyEntity;
import java.util.Optional;
import javax.annotation.concurrent.Immutable;
import javax.persistence.Column;
import org.joda.time.DateTime;

/** Singleton for ICANN's TMCH CA certificate revocation list (CRL). */
@javax.persistence.Entity
@Immutable
public final class TmchCrl extends CrossTldSingleton implements SqlOnlyEntity {

  @Column(name = "certificateRevocations", nullable = false)
  String crl;

  @Column(name = "updateTimestamp", nullable = false)
  DateTime updated;

  @Column(nullable = false)
  String url;

  /** Returns the singleton instance of this entity, without memoization. */
  public static Optional<TmchCrl> get() {
    return jpaTm().transact(() -> jpaTm().loadSingleton(TmchCrl.class));
  }

  /**
   * Change the singleton to a new ASCII-armored X.509 CRL.
   *
   * <p>Please do not call this function unless your CRL is properly formatted, signed by the root,
   * and actually newer than the one currently in the database.
   */
  public static void set(final String crl, final String url) {
    jpaTm()
        .transact(
            () -> {
              TmchCrl tmchCrl = new TmchCrl();
              tmchCrl.updated = jpaTm().getTransactionTime();
              tmchCrl.crl = checkNotNull(crl, "crl");
              tmchCrl.url = checkNotNull(url, "url");
              jpaTm().transactNew(() -> jpaTm().putWithoutBackup(tmchCrl));
            });
  }

  /** ASCII-armored X.509 certificate revocation list. */
  public final String getCrl() {
    return crl;
  }

  /** Returns the URL that the CRL was downloaded from. */
  public final String getUrl() {
    return crl;
  }

  /** Time we last updated the Database with a newer ICANN CRL. */
  public final DateTime getUpdated() {
    return updated;
  }
}
