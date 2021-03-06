/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.index.service.resources.list.api;

import org.opencastproject.index.service.exception.ListProviderException;
import org.opencastproject.security.api.Organization;

import java.util.Map;

public interface ResourceListProvider {

  /**
   * Returns the names of the list(s) provided
   * 
   * @return an array containing the lists available
   */
  String[] getListNames();

  /**
   * Returns the key-value list for the generator resource filtered with the given filter and based on the given
   * organization context.
   * 
   * @param listName
   *          the list name
   * @param query
   *          the list query
   * @param organization
   *          the organization context
   * @return the key-value list for the generator resource
   */
  Map<String, String> getList(String listName, ResourceListQuery query, Organization organization)
          throws ListProviderException;

}
