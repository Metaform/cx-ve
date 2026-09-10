import { Routes } from '@angular/router';
import { Dashboard } from './features/dashboard/dashboard';
import { RunDetail } from './features/run/run-detail';

export const routes: Routes = [
  { path: '', component: Dashboard },
  { path: 'runs/:id', component: RunDetail },
  { path: '**', redirectTo: '' }
];
