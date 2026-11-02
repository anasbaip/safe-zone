import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '../../core/services/auth';
import { ProfileService } from '../../core/services/profile';
import { User } from '../../core/models/user';
import { UpdateProfileDialog } from './update-profile-dialog/update-profile-dialog';
import { AnalyticsDashboard } from '../../shared/analytics-dashboard/analytics-dashboard';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [CommonModule, UpdateProfileDialog, AnalyticsDashboard],
  templateUrl: './profile.html',
  styleUrl: './profile.css',
})
export class Profile implements OnInit {
  readonly profile = signal<User | null>(null);
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly dialogOpen = signal(false);
  readonly toastMessage = signal('');

  constructor(
    private readonly profileService: ProfileService,
    private readonly auth: AuthService
  ) {}

  ngOnInit(): void {
    this.profileService.getProfile().subscribe({
      next: (user) => {
        this.profile.set(user);
        this.auth.updateCurrentUser(user);
        this.loading.set(false);
      },
      error: (error) => {
        this.errorMessage.set(error.error?.message || 'Unable to load your profile.');
        this.loading.set(false);
      }
    });
  }

  openEditDialog(): void {
    this.dialogOpen.set(true);
  }

  closeEditDialog(): void {
    this.dialogOpen.set(false);
  }

  onProfileSaved(user: User): void {
    this.profile.set(user);
    this.auth.updateCurrentUser(user);
    this.dialogOpen.set(false);
    this.showToast('Profile updated successfully.');
  }

  getDisplayName(user: User): string {
    return user.fullName || user.username || 'BUY01 member';
  }

  getInitials(user: User): string {
    const name = this.getDisplayName(user).trim();
    const parts = name.split(/\s+/);
    return parts.length > 1
      ? `${parts[0][0]}${parts[1][0]}`.toUpperCase()
      : name.slice(0, 2).toUpperCase();
  }

  showToast(message: string): void {
    this.toastMessage.set(message);
    setTimeout(() => this.toastMessage.set(''), 3500);
  }
}
