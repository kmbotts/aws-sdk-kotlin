/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package aws.sdk.kotlin.runtime.crt

import aws.sdk.kotlin.crt.io.MutableBuffer
import aws.smithy.kotlin.runtime.io.SdkBuffer
import aws.smithy.kotlin.runtime.io.readAvailable

internal actual fun transferRequestBody(outgoing: SdkBuffer, dest: MutableBuffer) {
    outgoing.readAvailable(dest.buffer)
}
