import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { ApiErrorResponse } from '../../models/auth.models';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './login.component.html',
})
export class LoginComponent {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly formBuilder = inject(FormBuilder);

  readonly form = this.formBuilder.nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  readonly errorMessage = signal<string | null>(null);
  readonly submitting = signal(false);
  // True right after landing here from a successful registration redirect
  // (see RegisterComponent) — `?registered=true` on the URL.
  // route.snapshot is a one-time read of the current route state; fine here
  // since this only needs to be checked once, when the component is created.
  readonly justRegistered = this.route.snapshot.queryParamMap.get('registered') === 'true';

  onSubmit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.errorMessage.set(null);
    this.submitting.set(true);

    this.authService.login(this.form.getRawValue()).subscribe({
      next: () => {
        this.router.navigate(['/users']);
      },
      error: (err: HttpErrorResponse) => {
        // AuthService.login() deliberately returns the SAME message whether
        // the username doesn't exist or the password is wrong (see
        // AuthService.java / InvalidCredentialsException on the backend) —
        // this just displays whatever the backend sent, faithfully
        // preserving that anti-enumeration behavior rather than trying to
        // be "smarter" about it on the frontend.
        this.submitting.set(false);
        const body = err.error as ApiErrorResponse | undefined;
        this.errorMessage.set(body?.message ?? 'Login failed. Please try again.');
      },
    });
  }
}
