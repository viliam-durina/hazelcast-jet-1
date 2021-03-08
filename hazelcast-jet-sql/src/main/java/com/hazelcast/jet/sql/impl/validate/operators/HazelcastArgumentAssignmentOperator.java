/*
 * Copyright (c) 2008-2021, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql.impl.validate.operators;

import org.apache.calcite.sql.SqlAsOperator;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;

/**
 * Hazelcast equivalent of {@link
 * org.apache.calcite.sql.fun.SqlArgumentAssignmentOperator}.
 */
@SuppressWarnings("JavadocReference")
public class HazelcastArgumentAssignmentOperator extends SqlAsOperator {

    private static final int PRECEDENCE = 20;

    public HazelcastArgumentAssignmentOperator() {
        super(
                "=>",
                SqlKind.ARGUMENT_ASSIGNMENT,
                PRECEDENCE,
                true,
                ReturnTypes.ARG0,
                InferTypes.RETURN_TYPE,
                OperandTypes.ANY_ANY
        );
    }

    @Override
    public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
        // Arguments are held in reverse order to be consistent with base class (AS).
        call.operand(1).unparse(writer, leftPrec, getLeftPrec());
        writer.keyword(getName());
        call.operand(0).unparse(writer, getRightPrec(), rightPrec);
    }

    @Override
    public final boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
        return true;
    }
}
