package com.example.dropsecure.core

fun main() {
    try { CoreTestSuite.runAll() } catch (e: AssertionError) { System.exit(1) }
    System.exit(0)
}
