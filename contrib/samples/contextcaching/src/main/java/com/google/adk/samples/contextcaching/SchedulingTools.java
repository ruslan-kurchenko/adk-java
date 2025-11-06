/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package src.main.java.com.google.adk.samples.contextcaching;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Demo tools for healthcare scheduling scenario.
 *
 * <p>These are simplified implementations to demonstrate context caching with function calling.
 */
public class SchedulingTools {

  /**
   * Searches for available appointment slots.
   *
   * @param date Preferred date (YYYY-MM-DD format)
   * @param specialty Provider specialty (e.g., "Primary Care", "Cardiology")
   * @return List of available time slots
   */
  public static List<String> searchAvailableSlots(String date, String specialty) {
    System.out.println(
        "[TOOL] searchAvailableSlots called: date=" + date + ", specialty=" + specialty);

    return List.of(
        "9:00 AM - Dr. Smith (Primary Care)",
        "2:00 PM - Dr. Johnson (Primary Care)",
        "4:30 PM - Dr. Williams (Primary Care)");
  }

  /**
   * Books an appointment.
   *
   * @param datetime Appointment date and time
   * @param provider Provider name
   * @return Confirmation message with appointment ID
   */
  public static String bookAppointment(String datetime, String provider) {
    System.out.println("[TOOL] bookAppointment called: " + datetime + " with " + provider);

    String appointmentId = "APT-" + System.currentTimeMillis();
    return "Appointment confirmed! ID: " + appointmentId + " on " + datetime + " with " + provider;
  }

  /**
   * Gets current date for scheduling reference.
   *
   * @return Current date in YYYY-MM-DD format
   */
  public static String getCurrentDate() {
    return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
  }
}
