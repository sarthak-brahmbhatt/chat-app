import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * The root component. bootstrapApplication (see main.ts) mounts exactly this
 * one component into index.html's <app-root> tag — everything else in the
 * app is rendered inside it via <router-outlet>, swapped based on the URL.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {}
