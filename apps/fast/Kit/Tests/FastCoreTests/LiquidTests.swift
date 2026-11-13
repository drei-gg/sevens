import Foundation
import Testing
@testable import FastCore

// Ported from StarMiles' fuel_liquid_test.dart (the parts that don't need Flutter), plus the
// circle's area check that replaces the rectangle's.

@Test func gyroMovesInTheCorrectedDirection() {
    for direction in [-1.0, 1.0] {
        var simulation = LiquidSimulation()
        simulation.gyroscope(z: direction)
        simulation.step(1.0 / 60)
        #expect(simulation.angle * direction > 0)
    }
}

@Test(arguments: [false, true], [-1.0, 1.0])
func gravityFollowsTwoCompleteTurnsWithoutASeam(landscape: Bool, direction: Double) {
    var simulation = LiquidSimulation()
    var previous = 0.0
    for degrees in stride(from: 0, through: 720, by: 10) {
        let angle = Double(degrees) * .pi / 180 * direction
        for _ in 0..<20 {
            simulation.accelerometer(x: 9.8 * (landscape ? cos(angle) : sin(angle)),
                                     y: 9.8 * (landscape ? -sin(angle) : cos(angle)), landscape: landscape)
            simulation.step(0.02)
            #expect(abs(simulation.angle - previous) < 0.15)
            previous = simulation.angle
        }
    }
    for _ in 0..<150 {
        simulation.accelerometer(x: landscape ? 9.8 : 0, y: landscape ? 0 : 9.8, landscape: landscape)
        simulation.step(0.02)
    }
    #expect(abs(simulation.angle - direction * .pi * 4) < 0.01)
    #expect(!simulation.isMoving)
}

@Test func idleWavesKeepTheRestingTiltAndRespondToNewMotion() {
    var simulation = LiquidSimulation()
    for _ in 0..<500 {
        simulation.accelerometer(x: 4, y: 9, landscape: false)
        simulation.step(1.0 / 60)
    }
    #expect(!simulation.isMoving)
    let angle = simulation.angle, phase = simulation.phase
    simulation.step(1.0 / 60)
    #expect(simulation.angle == angle)
    #expect(simulation.phase != phase)
    simulation.gyroscope(z: 2)
    simulation.step(1.0 / 60)
    #expect(simulation.isMoving)
    #expect(simulation.ripple > 0)
}

@Test func motionIsBoundedIgnoresInvalidSamplesAndSettles() {
    var simulation = LiquidSimulation()
    simulation.accelerometer(x: .nan, y: 9.8, landscape: false)
    simulation.gyroscope(z: .infinity)
    #expect(!simulation.isMoving)
    for _ in 0..<100 {
        simulation.accelerometer(x: 100, y: -100, landscape: false)
        simulation.gyroscope(z: 100)
        simulation.step(1.0 / 60)
        #expect(simulation.angle.isFinite)
        #expect(simulation.ripple <= 9)
    }
    for _ in 0..<600 where simulation.isMoving { simulation.step(1.0 / 60) }
    #expect(!simulation.isMoving)
    var fresh = LiquidSimulation()
    fresh.accelerometer(x: 0.01, y: 9.8, landscape: false)
    fresh.gyroscope(z: 0.02)
    #expect(!fresh.isMoving)
}

@Test(arguments: [0.0, 0.15, 0.5, 0.85, 1.0])
func circleOffsetLeavesTheFilledAreaBelowIt(fraction: Double) {
    let offset = LiquidGeometry.circleSurfaceOffset(fraction: fraction)
    var inside = 0, filled = 0
    for row in 0..<200 {
        for column in 0..<200 {
            let x = (Double(column) + 0.5) / 100 - 1, y = (Double(row) + 0.5) / 100 - 1
            guard x * x + y * y <= 1 else { continue }
            inside += 1
            if y >= offset { filled += 1 }
        }
    }
    #expect(abs(Double(filled) / Double(inside) - fraction) < 0.01)
    if fraction == 0.5 { #expect(abs(offset) < 0.0001) }
}
