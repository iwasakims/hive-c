/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.vector.expressions;

import java.util.Arrays;

import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorExpressionDescriptor;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.ql.metadata.HiveException;

/**
 * This is a superclass for unary long functions returning strings that operate directly on the
 * input and set the output.
 */
abstract public class LongToStringUnaryUDF extends VectorExpression {
  private static final long serialVersionUID = 1L;
  int inputColumn;
  int outputColumn;

  public LongToStringUnaryUDF(int inputColumn, int outputColumn) {
    this.inputColumn = inputColumn;
    this.outputColumn = outputColumn;
  }

  public LongToStringUnaryUDF() {
    super();
  }

  abstract protected void func(BytesColumnVector outputColVector, long[] vector, int i);

  @Override
  public void evaluate(VectorizedRowBatch batch) throws HiveException {

    if (childExpressions != null) {
      super.evaluateChildren(batch);
    }

    LongColumnVector inputColVector = (LongColumnVector) batch.cols[inputColumn];
    int[] sel = batch.selected;
    int n = batch.size;
    long[] vector = inputColVector.vector;
    BytesColumnVector outputColVector = (BytesColumnVector) batch.cols[outputColumn];
    outputColVector.initBuffer();

    if (n == 0) {
      //Nothing to do
      return;
    }

    boolean[] inputIsNull = inputColVector.isNull;
    boolean[] outputIsNull = outputColVector.isNull;

    // We do not need to do a column reset since we are carefully changing the output.
    outputColVector.isRepeating = false;

    if (inputColVector.isRepeating) {
      if (inputColVector.noNulls || !inputIsNull[0]) {
        // Set isNull before call in case it changes it mind.
        outputIsNull[0] = false;
        func(outputColVector, vector, 0);
      } else {
        outputIsNull[0] = true;
        outputColVector.noNulls = false;
      }
      outputColVector.isRepeating = true;
      return;
    }

    if (inputColVector.noNulls) {
      if (batch.selectedInUse) {

        // CONSIDER: For large n, fill n or all of isNull array and use the tighter ELSE loop.

        if (!outputColVector.noNulls) {
          for(int j = 0; j != n; j++) {
           final int i = sel[j];
           // Set isNull before call in case it changes it mind.
           outputIsNull[i] = false;
           func(outputColVector, vector, i);
         }
        } else {
          for(int j = 0; j != n; j++) {
            final int i = sel[j];
            func(outputColVector, vector, i);
          }
        }
      } else {
        if (!outputColVector.noNulls) {

          // Assume it is almost always a performance win to fill all of isNull so we can
          // safely reset noNulls.
          Arrays.fill(outputIsNull, false);
          outputColVector.noNulls = true;
        }
        for(int i = 0; i != n; i++) {
          func(outputColVector, vector, i);
        }
      }
    } else /* there are nulls in the inputColVector */ {

      // Carefully handle NULLs...

      // Handle case with nulls. Don't do function if the value is null,
      // because the data may be undefined for a null value.
      outputColVector.noNulls = false;

      if (batch.selectedInUse) {
        for(int j = 0; j != n; j++) {
          int i = sel[j];
          outputColVector.isNull[i] = inputColVector.isNull[i];
          if (!inputColVector.isNull[i]) {
            func(outputColVector, vector, i);
          }
        }
        outputColVector.isRepeating = false;
      } else {
        System.arraycopy(inputColVector.isNull, 0, outputColVector.isNull, 0, n);
        for(int i = 0; i != n; i++) {
          if (!inputColVector.isNull[i]) {
            func(outputColVector, vector, i);
          }
        }
      }
    }
  }


  @Override
  public int getOutputColumn() {
    return outputColumn;
  }

  public void setOutputColumn(int outputColumn) {
    this.outputColumn = outputColumn;
  }

  public int getInputColumn() {
    return inputColumn;
  }

  public void setInputColumn(int inputColumn) {
    this.inputColumn = inputColumn;
  }

  @Override
  public String vectorExpressionParameters() {
    return "col " + inputColumn;
  }

  @Override
  public VectorExpressionDescriptor.Descriptor getDescriptor() {
    VectorExpressionDescriptor.Builder b = new VectorExpressionDescriptor.Builder();
    b.setMode(VectorExpressionDescriptor.Mode.PROJECTION)
        .setNumArguments(1)
        .setArgumentTypes(
            VectorExpressionDescriptor.ArgumentType.INT_FAMILY)
        .setInputExpressionTypes(
            VectorExpressionDescriptor.InputExpressionType.COLUMN);
    return b.build();
  }
}
