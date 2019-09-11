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
package ddf.sdk.plugin.postquery;

import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.catalog.data.impl.MetacardImpl;
import ddf.catalog.data.impl.ResultImpl;
import ddf.catalog.data.types.experimental.Extracted;
import ddf.catalog.operation.QueryResponse;
import ddf.catalog.operation.impl.QueryResponseImpl;
import ddf.catalog.plugin.PluginExecutionException;
import ddf.catalog.plugin.PostQueryPlugin;
import ddf.catalog.plugin.StopProcessingException;
import ddf.catalog.validation.MetacardValidator;
import java.io.StringReader;
import java.util.stream.Collectors;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates the query results from a query as a PostQueryPLugin
 *
 * @author Shaun Morris
 */
public class DummyValidationPostQueryPlugin implements PostQueryPlugin {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(DummyValidationPostQueryPlugin.class.getName());

  private MetacardValidator validator;

  private XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();

  public QueryResponse process(QueryResponse input)
      throws PluginExecutionException, StopProcessingException {
    String methodName = "process()";
    LOGGER.debug("ENTERING: {}", methodName);

    if (input != null) {
      input =
          new QueryResponseImpl(
              input.getRequest(),
              input
                  .getResults()
                  .stream()
                  .map(
                      r ->
                          (r.getMetacard().getAttribute(Extracted.EXTRACTED_TEXT) == null
                                      || StringUtils.isEmpty(
                                          (String)
                                              r.getMetacard()
                                                  .getAttribute(Extracted.EXTRACTED_TEXT)
                                                  .getValue()))
                                  && StringUtils.isNotEmpty(r.getMetacard().getMetadata())
                              ? new ResultImpl(enrichMetacard(r.getMetacard()))
                              : r)
                  .collect(Collectors.toList()),
              input.getHits());
    }

    LOGGER.debug("EXITING: {}", methodName);

    return input;
  }

  private Metacard enrichMetacard(Metacard metacard) {
    try {
      XMLEventReader xmlEventReader =
          xmlInputFactory.createXMLEventReader(new StringReader(metacard.getMetadata()));
      String longest = "";
      while (xmlEventReader.hasNext()) {
        XMLEvent xmlEvent = xmlEventReader.nextEvent();
        if (xmlEvent.isCharacters()) {
          String data = xmlEvent.asCharacters().getData();
          longest = data.length() > longest.length() ? data : longest;
        }
      }
      MetacardImpl metacardImpl = new MetacardImpl(metacard);
      metacardImpl.setAttribute(new AttributeImpl(Extracted.EXTRACTED_TEXT, longest));
      return metacardImpl;
    } catch (XMLStreamException e) {
      LOGGER.error("FAILURE", e);
      return metacard;
    }
  }
}
