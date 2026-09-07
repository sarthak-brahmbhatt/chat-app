package com.chatapp.chatservice.bot.clinic.repository;

import com.chatapp.chatservice.bot.clinic.entity.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {

    List<Doctor> findByActiveTrueOrderByNameAsc();

    /**
     * The distinct specialties actually present among ACTIVE doctors.
     *
     * <p>Injected into the system prompt as a closed list the model is told never
     * to depart from (CLAUDE.md 3.9 §6.1). That instruction is what makes "sorry,
     * we don't have a dermatologist" work: without a list to check against, a
     * model asked for a dermatologist will happily invent one, because inventing
     * a plausible specialty is a much more natural continuation than refusing.
     *
     * <p>Read at request time rather than cached or hardcoded, so seeding a new
     * doctor is the entire operation needed to make their specialty bookable —
     * no redeploy, no second place to update, no window where the list and the
     * doctors disagree.
     */
    @Query("SELECT DISTINCT d.specialty FROM Doctor d WHERE d.active = true ORDER BY d.specialty ASC")
    List<String> findActiveSpecialties();

    boolean existsByNameAndSpecialty(String name, String specialty);
}
