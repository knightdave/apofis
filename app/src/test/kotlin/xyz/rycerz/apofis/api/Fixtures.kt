package xyz.rycerz.apofis.api

import java.io.File

internal fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

internal fun fixtureNames(): Set<String> =
    File(requireNotNull(object {}.javaClass.getResource("/fixtures")).toURI()).list()!!.toSet()
