/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.activiti.form.model;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Erik Winlof
 *
 */
public class FormContainer extends FormField {

  private static final long serialVersionUID = 1L;

  protected List<FormField> fields = new ArrayList<FormField>();
  protected Integer columns;
  protected boolean singleRow;

  
	/**
	 * @return the singleRow
	 */
	public boolean isSingleRow() {
		return singleRow;
	}

	
	/**
	 * @param pSingleRow the singleRow to set
	 */
	public void setSingleRow(boolean pSingleRow) {
		singleRow = pSingleRow;
	}

	public List<FormField> getFields() {
    return fields;
  }

  public void setFields(List<FormField> fields) {
    this.fields = fields;
  }

  public Integer getColumns() {
    return columns;
  }

  public void setColumns(Integer pColumns) {
    columns = pColumns;
  }
}