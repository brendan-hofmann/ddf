/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.spatial.geocoding.create;

import java.util.Set;
import org.apache.commons.collections.CollectionUtils;
import org.codice.countrycode.CountryCodeConverter;
import org.codice.countrycode.converter.Converter;
import org.codice.countrycode.standard.CountryCode;
import org.codice.countrycode.standard.StandardProvider;
import org.codice.countrycode.standard.StandardRegistry;
import org.codice.countrycode.standard.StandardRegistryImpl;
import org.codice.ddf.spatial.geocoding.GeoEntry;
import org.codice.ddf.spatial.geocoding.GeoEntryCreator;

/**
 * An implementation of {@link GeoEntryCreator} for GeoNames data obtained from <a
 * href="http://download.geonames.org/export/dump">geonames.org</a>.
 */
public class GeoNamesCreator implements GeoEntryCreator {
  private Converter converter;
  private StandardProvider isoStandard;

  public GeoNamesCreator() {
    this.converter = new CountryCodeConverter();
    StandardRegistry registry = StandardRegistryImpl.getInstance();
    isoStandard = registry.lookup("ISO3166", "1");
  }

  @Override
  public GeoEntry createGeoEntry(final String line, String entryResource) {
    // Passing a negative value to preserve empty fields.
    final String[] fields = line.split("\\t", -1);

    String countryCode = fields[8];
    Set<CountryCode> countryCodes =
        converter.fromAlpha2(
            fields[8].trim(), isoStandard.getStandard(), isoStandard.getStandard());
    if (CollectionUtils.isNotEmpty(countryCodes)) {
      countryCode = countryCodes.iterator().next().getAsFormat("alpha3");
    }

    return new GeoEntry.Builder()
        .name(fields[1])
        .latitude(Double.parseDouble(fields[4]))
        .longitude(Double.parseDouble(fields[5]))
        .featureCode(fields[7])
        .population(Long.parseLong(fields[14]))
        .alternateNames(fields[3])
        .countryCode(countryCode)
        .importLocation(entryResource)
        .build();
  }
}
