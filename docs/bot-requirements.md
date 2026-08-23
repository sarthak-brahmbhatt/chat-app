# ChatBot Requirements — Doctor's Assistant

The source requirements for the DoctorAssistant bot, recorded verbatim.

Kept in the repo because CLAUDE.md 3.9 treats the three sample conversations
below as the target behaviour, and a spec that lives only in a chat window is
one nobody can check the implementation against later. The design decisions
made *from* these requirements are in CLAUDE.md 3.9; this file is the
requirement itself, unedited.

---

To implement "Doctor's Assistant" — a chatbot that can answer queries posted in
natural language regarding doctor's availability.

The Chatbot has to be implemented using

1. Responses API
2. Tool Calling with Responses API

The bot can be implemented either in Python or Java. Refer to OpenAI
Documentation at https://developers.openai.com/api/docs

Detailed requirements are listed below:

1. The list of doctors, their specialty/department and their availability
   (dates/days and timings) to be configured in a database.

2. The bot should support (but not limited to) the following types of
   conversations:

   **(i)**

   1. Agent: Hello and welcome to the Super Clinic
   2. Caller1: Hi can I have an appointment with Dr X
   3. Agent: Sure, what date and time would you like the appointment
   4. Caller1: I would like to meet him tomorrow at 10 AM
   5. Agent: Sorry, he is not available at 10 AM, are you available at 11 AM
   6. Caller1: Ok
   7. Agent: Great, call is booked

   **(ii)**

   1. Agent: Hello and welcome to the Super Clinic
   2. Caller2: Hi, I have been having these rashes for the past few days. Would
      like to meet a doctor, could you help
   3. Agent: You would have to see a dermatologist for this problem. Sorry, we
      do not have any doctors with that speciality at our clinic. Is there
      anything else that I could help you with?
   4. Caller2: No
   5. Agent: Thank you for contacting Super Clinic. Have a good day!

   **(iii)**

   1. Agent: Hello and welcome to the Super Clinic
   2. Caller3: Hi, I fell down while playing badminton — my ankle is swollen.
      Would like to meet a doctor today, could you help
   3. Agent: Sure, which of the following orthopedic doctor(s) would you like to
      meet? \<lists all the specialists\>
   4. Caller3: I would like to meet Dr.X
   5. Agent: He is not available today, do you want to meet him tomorrow at
      10:30 AM
   6. Caller3: oh! in that case can I meet Dr.Y today
   7. Agent: Sure, he is available at 11:00 AM. Is that fine?
   8. Caller2: Ok
   9. Agent: Great, call is booked

3. Whenever an appointment is booked, the database to be modified to indicate
   the unavailability of the doctor for that slot [for future bookings]. The
   details of the patient may be collected and stored for reference.

---

## How this maps onto what is built

Cross-references, so a reader can check the implementation against the spec
rather than taking a summary's word for it.

| Requirement | Where it lives |
|---|---|
| Responses API | `bot/OpenAiBotBrain.java` — official `com.openai:openai-java` SDK, `client.responses().create(...)` |
| **Tool calling** | **NOT YET BUILT — Version 2.** See the note below; this is required scope, not optional. |
| Doctors, specialty, availability in a database | `doctors`, `doctor_availability` (recurring weekly pattern, 30-min slots), seeded by `bot/DoctorSeedData.java` |
| Conversation (i) — named doctor, time unavailable, alternative offered | `bot/BotPromptBuilder.java`, "OFFERING TIMES" |
| Conversation (ii) — specialty not offered, then close out | `bot/BotPromptBuilder.java`, "SPECIALTIES"; driven by the DISTINCT specialty list read from the database each turn |
| Conversation (iii) — symptoms → specialty, list all specialists, switch doctor | `bot/BotPromptBuilder.java`, "FINDING A DOCTOR" |
| Booking blocks the slot for future bookings | `appointments` row + the subtraction in `bot/AvailabilityService.java`; re-validated in `bot/BookingService.java` before every insert |
| Patient details stored for reference | `appointments.user_id` → `users`, which already holds username, first and last name. The spec says "may be", and a foreign key to the existing account is stronger than re-collecting details the system already has. |

## Version 1 vs Version 2 — note on tool calling

The requirement lists Responses API **and** Tool Calling as things the bot has
to be implemented using. Both are in scope; they were deliberately sequenced
rather than built together.

Version 1 (built) uses the Responses API with **no tools**, stuffing all clinic
data into the prompt each turn. That is knowingly the naive approach, chosen so
its limits get measured rather than described — see `bot_token_usage` and
CLAUDE.md 3.9.

Version 2 (not built) adds tool calling, which is where the requirement is
actually satisfied in full. **Version 1 alone does not meet requirement item 2
of "implemented using".**
