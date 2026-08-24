package com.chatapp.chatservice.bot.clinic;

import com.chatapp.chatservice.bot.clinic.entity.Appointment;
import com.chatapp.chatservice.bot.clinic.repository.AppointmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Turns a model's booking REQUEST into a booking, or refuses it
 * (CLAUDE.md 3.9 §6.4).
 *
 * <p>This is the trust boundary. Everything reaching it came out of a language
 * model, including the slot id and the date, and the data the model reasoned
 * over was a snapshot taken at the start of the turn — not a lock, and already
 * seconds stale by the time it decided. So nothing it says is taken on trust:
 * the slot is re-checked against live data, the date is re-checked against the
 * slot's own weekday, and only then is a row written.
 *
 * <p><b>Validate and write BEFORE replying, always.</b> §6.4 puts the ordering
 * first for a reason — reply-then-write leaves a window where the user has been
 * told "booked for Tuesday at 9" and the insert then fails, and there is no
 * honest way to recover from that. Writing first means a confirmation is only
 * ever sent about a row that exists.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    /**
     * §6.4's Java-written replacement for the model's reply when validation
     * fails. Names the reason at the level the user cares about and offers the
     * obvious next step, without exposing which of the several checks tripped —
     * "that slot was just taken" and "you picked a Tuesday for a Monday clinic"
     * lead to the same next action from the user's side.
     */
    private static final String SLOT_UNAVAILABLE_REPLY =
            "Sorry — that slot isn't available any more. Would you like to pick another time?";

    private final AvailabilityService availabilityService;
    private final AppointmentRepository appointmentRepository;
    private final Clock clock;

    public BookingService(
            AvailabilityService availabilityService,
            AppointmentRepository appointmentRepository,
            Clock clock) {
        this.availabilityService = availabilityService;
        this.appointmentRepository = appointmentRepository;
        this.clock = clock;
    }

    /**
     * Runs §6.4's checks in order and inserts if they all pass.
     *
     * @param request  the slot and date the model asked for, plus the text to
     *                 send if it succeeds
     * @param userId   the authenticated sender — taken from the WebSocket
     *                 session, never from anything the model produced, so a
     *                 hallucinated user id cannot book on someone else's behalf
     */
    @Transactional
    public BookingOutcome book(BookingRequest request, long userId) {
        Optional<Long> availabilityId = Optional.ofNullable(request.availabilityId());
        Optional<LocalDate> date = parseDate(Optional.ofNullable(request.bookedForDate()));

        // Structured outputs guarantee both fields are PRESENT in the JSON; they
        // do not guarantee either is non-null, since the schema marks them
        // nullable so a NONE turn can leave them empty. A BOOK turn that leaves
        // them empty is the model contradicting itself — no slot was actually
        // chosen, so there is nothing to validate and nothing to book.
        if (availabilityId.isEmpty() || date.isEmpty()) {
            log.warn("Model asked to BOOK without a usable availability_id/booked_for_date: {}", request);
            return BookingOutcome.rejected(SLOT_UNAVAILABLE_REPLY);
        }

        LocalDate bookedFor = date.get();

        // Not one of §6.4's numbered steps, but it belongs before them: the
        // subtraction in AvailabilityService is date-agnostic and will happily
        // report last Monday's 9am as free, because nothing has been booked
        // against a date in the past. Only a check against today catches it.
        LocalDate today = LocalDate.now(clock);
        if (bookedFor.isBefore(today)) {
            log.warn("Model asked to BOOK a date in the past: {} (today is {})", bookedFor, today);
            return BookingOutcome.rejected(SLOT_UNAVAILABLE_REPLY);
        }

        // Step 1 — is the slot genuinely still free, right now? The prompt's
        // snapshot could be stale by a whole conversation's worth of time, and
        // another user's booking in that gap is exactly the case this catches.
        Optional<FreeSlot> slot = availabilityService.findFreeSlot(availabilityId.get(), bookedFor);
        if (slot.isEmpty()) {
            log.info("Rejected booking: availability {} is not free on {}", availabilityId.get(), bookedFor);
            return BookingOutcome.rejected(SLOT_UNAVAILABLE_REPLY);
        }

        // Step 2 — the weekday guard. AvailabilityService.freeSlotsOn already
        // filters the pattern to the date's own weekday, so a mismatched pair
        // returns nothing and step 1 has in fact already rejected it. Keeping
        // the check anyway: it is the one §6.4 asks for by name, it states the
        // invariant where a reader looking for it will look, and it stops being
        // redundant the moment anyone adds a slot lookup that isn't date-scoped.
        FreeSlot chosen = slot.get();
        if (!chosen.date().equals(bookedFor)) {
            log.warn("Rejected booking: slot {} resolved to {} but the request was for {}",
                    chosen.availabilityId(), chosen.date(), bookedFor);
            return BookingOutcome.rejected(SLOT_UNAVAILABLE_REPLY);
        }

        // Step 3 — insert. Times are copied off the slot rather than taken from
        // the model: they are denormalised onto the row on purpose (see
        // Appointment), and the authoritative value is the pattern's, not
        // whatever the model echoed back.
        Appointment appointment = new Appointment(
                chosen.doctorId(),
                chosen.availabilityId(),
                bookedFor,
                chosen.startTime(),
                chosen.endTime(),
                userId,
                clock.instant());

        try {
            Appointment saved = appointmentRepository.saveAndFlush(appointment);
            log.info("Booked appointment {} for user {} with {} on {} at {}",
                    saved.getId(), userId, chosen.doctorName(), bookedFor, chosen.startTime());
            // Step 4 — and only now is the model's own confirmation text allowed
            // out, because there is finally a row for it to be true about.
            return BookingOutcome.booked(request.confirmationText(), saved.getId());
        } catch (DataIntegrityViolationException e) {
            // The unique constraint on (availability_id, booked_for_date) firing
            // means another booking landed between step 1's read and this write.
            // saveAndFlush rather than save is what makes that surface here, as a
            // catchable exception, instead of at transaction commit — after this
            // method has already returned a success the caller would act on.
            log.info("Rejected booking: availability {} on {} was taken concurrently",
                    chosen.availabilityId(), bookedFor);
            return BookingOutcome.rejected(SLOT_UNAVAILABLE_REPLY);
        }
    }

    /**
     * The schema constrains this field to a string, not to a DATE — "next
     * Tuesday" or "2026-02-30" both satisfy it. Parsing is therefore a real
     * validation step, and a failure here is a model error, not a user error.
     */
    private Optional<LocalDate> parseDate(Optional<String> raw) {
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(raw.get()));
        } catch (DateTimeParseException e) {
            log.warn("Model produced an unparseable booked_for_date '{}'", raw.get());
            return Optional.empty();
        }
    }
}
