import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { ApiErrorResponse } from '../../models/auth.models';

/**
 * Reactive Forms (FormBuilder/FormGroup), not template-driven (`[(ngModel)]`)
 * forms — the more idiomatic Angular approach: the form's state and
 * validation rules live in the TypeScript class as data (a FormGroup), and
 * the template just binds to it, rather than the template itself carrying
 * validation logic. `nonNullable` on the FormBuilder means each control's
 * value type is `string`, not `string | null` — matches RegisterRequest's
 * fields directly, no extra null-handling needed when submitting.
 */
@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './register.component.html',
})
export class RegisterComponent {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly formBuilder = inject(FormBuilder);

  readonly form = this.formBuilder.nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
    firstName: ['', Validators.required],
    // No Validators.required here — matches user-service's RegisterRequest,
    // where lastName is collected but deliberately NOT part of the
    // required-field validation (see user-service/dto/RegisterRequest.java).
    lastName: [''],
  });

  readonly errorMessage = signal<string | null>(null);
  readonly submitting = signal(false);

  onSubmit(): void {
    if (this.form.invalid) {
      // Marks every control "touched" so the template's error messages
      // (which only show for touched-and-invalid controls) become visible —
      // otherwise a user who never focused a required field would submit
      // and see nothing telling them what's wrong.
      this.form.markAllAsTouched();
      return;
    }

    this.errorMessage.set(null);
    this.submitting.set(true);

    this.authService.register(this.form.getRawValue()).subscribe({
      next: () => {
        this.router.navigate(['/login'], { queryParams: { registered: 'true' } });
      },
      error: (err: HttpErrorResponse) => {
        // The real backend error, not a generic fallback: user-service
        // returns {"message": "..."} for both 400 (missing/blank field —
        // duplicate-username case is 409, but blank-field validation is also
        // enforced client-side above, so this mostly surfaces the 409 case
        // in practice: duplicate username, which can ONLY be known by
        // asking the backend) and any other failure, all in the same shape.
        this.submitting.set(false);
        const body = err.error as ApiErrorResponse | undefined;
        this.errorMessage.set(body?.message ?? 'Registration failed. Please try again.');
      },
    });
  }
}
