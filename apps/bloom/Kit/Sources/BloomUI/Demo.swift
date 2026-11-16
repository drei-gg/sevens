import BloomWire
import Foundation

#if DEBUG
  public enum BloomDemo {
    public static func make() -> Archive {
      var a = Archive()
      let cal = Calendar.current
      for offset in [80, 52, 24] {
        for n in 0..<5 {
          var d = DayLog()
          d.day = BloomDate.day(cal.date(byAdding: .day, value: -offset + n, to: Date())!)
          d.flow = n < 2 ? "medium" : "light"
          d.period_start = n == 0
          d.symptoms = ["Cramps": n < 2 ? 2 : 1, "Energy": 1]
          a.days.append(d)
        }
      }
      var d = DayLog()
      d.symptoms = ["Headache": 1, "Mood changes": 2, "Sleep difficulty": 1]
      d.notes = "A quieter day helped."
      a.days.append(d)
      var p = Plan()
      p.name = "Daily pill"
      p.start = BloomDate.day(cal.date(byAdding: .day, value: -4, to: Date())!)
      p.initial_stock = 28
      a.plans.append(p)
      var appointment = Appointment()
      appointment.title = "Routine check-in"
      appointment.at = Int64(Date().addingTimeInterval(3 * 86400).timeIntervalSince1970)
      a.appointments.append(appointment)
      return a
    }
  }
#endif
