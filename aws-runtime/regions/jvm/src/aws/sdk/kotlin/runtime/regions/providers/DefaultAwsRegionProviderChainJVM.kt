/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package aws.sdk.kotlin.runtime.regions.providers

public actual class DefaultAwsRegionProviderChain public actual constructor() :
    AwsRegionProvider,
    AwsRegionProviderChain(
        JvmSystemPropRegionProvider(),
        EnvironmentRegionProvider()
    )
