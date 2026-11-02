# Frontend

This project was generated using [Angular CLI](https://github.com/angular/angular-cli) version 22.0.4.

## Structure
```bash 
src/app/
├── core/
│   ├── guards/
│   │   ├── auth.guard.ts
│   │   └── role.guard.ts
│   ├── interceptors/
│   │   └── jwt.interceptor.ts
│   ├── models/
│   │   ├── user.model.ts
│   │   ├── product.model.ts
│   │   └── media.model.ts
│   └── services/
│       ├── auth.service.ts
│       ├── product.service.ts
│       └── media.service.ts
├── features/
│   ├── auth/
│   │   ├── login/login.component.ts
│   │   └── register/register.component.ts
│   ├── products/
│   │   └── product-list/product-list.component.ts
│   └── seller/
│       ├── dashboard/seller-dashboard.component.ts
│       └── media-manager/media-manager.component.ts
├── shared/
│   └── navbar/navbar.component.ts
├── app.config.ts
├── app.routes.ts
└── app.component.ts
```

## Development server

To start a local development server, run:

```bash
ng serve
```

Once the server is running, open your browser and navigate to `http://localhost:4200/`. The application will automatically reload whenever you modify any of the source files.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the project run:

```bash
ng build
```

This will compile your project and store the build artifacts in the `dist/` directory. By default, the production build optimizes your application for performance and speed.

## Running unit tests

To execute unit tests with the [Vitest](https://vitest.dev/) test runner, use the following command:

```bash
ng test
```

## Running end-to-end tests

Start the full local stack from the repository root with `bash scripts/run-local.sh`.
Then, from `frontend/`:

```bash
npm run test:e2e
```

The suite uses Puppeteer, a real Chrome browser, and the running HTTPS application.
No API responses are mocked. It creates unique accounts/product fixtures and cleans
up its own records, including orders, carts and checkout receipts. Fixture access is
restricted to the `buy02-local` Compose project. Product images are not uploaded to
Cloudinary during these tests.

Set `PUPPETEER_EXECUTABLE_PATH` to select Chrome, or install the bundled browser with
`npx puppeteer browsers install chrome`. An installed `/usr/bin/google-chrome` is
detected automatically. CI installs Puppeteer's matching Chrome build rather than
using the runner's independently updated browser. Use `E2E_NO_SANDBOX=1 npm run test:e2e` only where the browser
sandbox is unavailable; CI uses this on its disposable runner.

Coverage includes search/filtering, pagination, checkout, seller delivery, cancelled
order exclusion, mixed-seller and customer isolation, analytics and date filters
at mobile width. Failed scenarios save screenshots in `e2e/artifacts/`.
See the [root README](../README.md#browser-end-to-end-tests) for details.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
