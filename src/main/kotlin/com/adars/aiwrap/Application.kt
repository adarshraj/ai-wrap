package com.adars.aiwrap

import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.annotations.QuarkusMain

@QuarkusMain
class Application {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Quarkus.run(*args)
        }
    }
}
